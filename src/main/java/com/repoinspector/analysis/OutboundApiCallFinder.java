package com.repoinspector.analysis;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.JavaRecursiveElementVisitor;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiNewExpression;
import com.intellij.psi.PsiType;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.AnnotatedElementsSearch;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import com.repoinspector.model.EndpointInfo;
import com.repoinspector.model.OutboundApiCall;
import com.repoinspector.model.OutboundApiCall.ClientType;
import com.repoinspector.model.OutboundApiCall.ConfidenceLevel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scans the project's source code for outbound HTTP API calls using PSI analysis.
 *
 * <p>Supports the following HTTP client libraries:
 * <ul>
 *   <li>Spring RestTemplate</li>
 *   <li>Spring WebClient</li>
 *   <li>OkHttp3 ({@code Request.Builder})</li>
 *   <li>Spring Cloud OpenFeign ({@code @FeignClient})</li>
 *   <li>{@code java.net.HttpURLConnection}</li>
 *   <li>Apache HttpClient 4/5 ({@code HttpGet}, {@code HttpPost}, etc.)</li>
 * </ul>
 *
 * <p>All PSI access is wrapped in a read action. Safe to call from a background thread.
 */
public final class OutboundApiCallFinder {

    private static final Logger LOG = Logger.getInstance(OutboundApiCallFinder.class);

    // RestTemplate method names and the HTTP verb they imply
    private static final Map<String, String> REST_TEMPLATE_METHODS = Map.of(
            "getForObject", "GET",
            "getForEntity", "GET",
            "postForObject", "POST",
            "postForEntity", "POST",
            "put", "PUT",
            "delete", "DELETE",
            "patchForObject", "PATCH",
            "exchange", "DYNAMIC"  // HTTP method comes from arg[1]
    );

    // Apache HttpClient request classes and their HTTP verbs
    private static final Map<String, String> APACHE_REQUEST_CLASSES = Map.of(
            "org.apache.http.client.methods.HttpGet", "GET",
            "org.apache.http.client.methods.HttpPost", "POST",
            "org.apache.http.client.methods.HttpPut", "PUT",
            "org.apache.http.client.methods.HttpDelete", "DELETE",
            "org.apache.http.client.methods.HttpPatch", "PATCH",
            // Apache HC 5 package
            "org.apache.hc.client5.http.classic.methods.HttpGet", "GET",
            "org.apache.hc.client5.http.classic.methods.HttpPost", "POST",
            "org.apache.hc.client5.http.classic.methods.HttpPut", "PUT",
            "org.apache.hc.client5.http.classic.methods.HttpDelete", "DELETE"
    );

    private static final String[] WEB_CLIENT_VERBS = {"get", "post", "put", "delete", "patch"};

    private OutboundApiCallFinder() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Scans the entire project for outbound HTTP calls across all supported client types.
     *
     * @param project the current project
     * @return list of detected {@link OutboundApiCall} records, one per call site
     */
    public static List<OutboundApiCall> findAll(Project project) {
        List<OutboundApiCall> results = new ArrayList<>();
        GlobalSearchScope scope = GlobalSearchScope.projectScope(project);

        // ReferencesSearch / AnnotatedElementsSearch must NOT be called inside runReadAction —
        // they manage read-lock acquisition internally (via processFilesConcurrentlyDespiteWriteActions).
        // We only need a ProgressIndicator installed on the thread.
        ProgressManager.getInstance().runProcess(() -> {
            scanRestTemplate(project, scope, results);
            // scanWebClient uses visitor pattern — needs an explicit read action
            ApplicationManager.getApplication().runReadAction(() ->
                    scanWebClient(project, scope, results));
            scanOkHttp(project, scope, results);
            scanFeignClients(project, scope, results);
            scanHttpUrlConnection(project, scope, results);
            scanApacheHttpClient(project, scope, results);
        }, new EmptyProgressIndicator());

        return results;
    }

    /**
     * Returns only the outbound calls reachable from the given inbound {@code endpoint}.
     * Uses {@link CallChainAnalyzer} for DFS traversal and filters {@link #findAll} results.
     *
     * @param endpoint the inbound Spring endpoint to trace from
     * @param project  the current project
     * @return filtered list of outbound calls reachable from that endpoint
     */
    public static List<OutboundApiCall> findReachableFrom(EndpointInfo endpoint, Project project) {
        // Collect reachable PsiMethod set via existing DFS analyzer
        Set<PsiMethod> reachable = new HashSet<>();
        var chain = CallChainAnalyzer.analyze(endpoint, project);
        ApplicationManager.getApplication().runReadAction(() -> {
            for (var node : chain) {
                if (node.psiMethod() != null) {
                    reachable.add(node.psiMethod());
                }
            }
        });

        List<OutboundApiCall> all = findAll(project);
        if (reachable.isEmpty()) return all; // conservative: cannot filter

        return all.stream()
                .filter(c -> c.psiMethod() == null || reachable.contains(c.psiMethod()))
                .toList();
    }

    // -------------------------------------------------------------------------
    // Per-client scanners
    // -------------------------------------------------------------------------

    private static void scanRestTemplate(Project project, GlobalSearchScope scope,
                                          List<OutboundApiCall> results) {
        // Lookup inside read action; search runs outside
        PsiClass restTemplateClass = ApplicationManager.getApplication().runReadAction(
                (Computable<PsiClass>) () -> JavaPsiFacade.getInstance(project).findClass(
                        "org.springframework.web.client.RestTemplate",
                        GlobalSearchScope.allScope(project)));
        if (restTemplateClass == null) {
            LOG.info("[OutboundApiCallFinder] RestTemplate class not found in project classpath — skipping");
            return;
        }
        LOG.info("[OutboundApiCallFinder] RestTemplate class found, scanning for usages...");

        for (Map.Entry<String, String> entry : REST_TEMPLATE_METHODS.entrySet()) {
            String methodName = entry.getKey();
            String impliedVerb = entry.getValue();

            PsiMethod[] methods = ApplicationManager.getApplication().runReadAction(
                    (Computable<PsiMethod[]>) () -> restTemplateClass.findMethodsByName(methodName, false));
            LOG.info("[OutboundApiCallFinder] RestTemplate." + methodName + " — " + methods.length + " PSI method(s) found");
            for (PsiMethod method : methods) {
                int[] refCount = {0};
                ReferencesSearch.search(method, scope).forEach(ref -> {  // outside runReadAction
                    refCount[0]++;
                    PsiMethodCallExpression call =
                            PsiTreeUtil.getParentOfType(ref.getElement(), PsiMethodCallExpression.class);
                    if (call == null) return true;

                    PsiExpression[] args = call.getArgumentList().getExpressions();
                    if (args.length == 0) return true;

                    // URL is always arg[0]
                    UrlResolver.ResolvedUrl resolved = UrlResolver.resolve(args[0], project);

                    // HTTP method: use implied verb unless "DYNAMIC" (exchange)
                    String httpMethod = impliedVerb;
                    if ("DYNAMIC".equals(impliedVerb) && args.length >= 2) {
                        httpMethod = extractHttpMethodEnumArg(args[1]);
                    }

                    // Body: for POST/PUT/PATCH the entity/request-body is arg[1] or arg[2]
                    List<String> bodyFields = null;
                    if (("POST".equals(httpMethod) || "PUT".equals(httpMethod)
                            || "PATCH".equals(httpMethod)) && args.length > 1) {
                        bodyFields = extractBodyFields(args[args.length - 2], project);
                    }

                    PsiMethod callerMethod =
                            PsiTreeUtil.getParentOfType(call, PsiMethod.class);
                    addResult(results, callerMethod, httpMethod, resolved,
                            Map.of(), Map.of(), bodyFields, ClientType.REST_TEMPLATE);
                    return true;
                });
                LOG.info("[OutboundApiCallFinder] RestTemplate." + methodName + " — " + refCount[0] + " reference(s) in project scope");
            }
        }
    }

    private static void scanWebClient(Project project, GlobalSearchScope scope,
                                       List<OutboundApiCall> results) {
        JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
        PsiClass webClientClass = facade.findClass(
                "org.springframework.web.reactive.function.client.WebClient",
                GlobalSearchScope.allScope(project));
        if (webClientClass == null) return;

        // Walk all project files looking for WebClient verb calls
        for (PsiFile file : collectProjectFiles(project, scope)) {
            file.accept(new JavaRecursiveElementVisitor() {
                @Override
                public void visitMethodCallExpression(PsiMethodCallExpression call) {
                    super.visitMethodCallExpression(call);

                    String name = call.getMethodExpression().getReferenceName();
                    String httpVerb = null;
                    for (String verb : WEB_CLIENT_VERBS) {
                        if (verb.equals(name)) {
                            httpVerb = verb.toUpperCase();
                            break;
                        }
                    }
                    if (httpVerb == null) return;

                    // Verify qualifier type is WebClient
                    PsiExpression qualifier =
                            call.getMethodExpression().getQualifierExpression();
                    if (qualifier == null) return;
                    PsiType type = qualifier.getType();
                    if (type == null || !type.getCanonicalText().contains("WebClient")) return;

                    // Walk the chain forward to find .uri(...)
                    UrlResolver.ResolvedUrl resolved = extractWebClientUri(call, project);
                    Map<String, String> headers = extractWebClientHeaders(call, project);
                    List<String> bodyFields = extractWebClientBody(call, project);

                    PsiMethod callerMethod = PsiTreeUtil.getParentOfType(call, PsiMethod.class);
                    addResult(results, callerMethod, httpVerb, resolved,
                            headers, Map.of(), bodyFields, ClientType.WEB_CLIENT);
                }
            });
        }
    }

    private static void scanOkHttp(Project project, GlobalSearchScope scope,
                                    List<OutboundApiCall> results) {
        PsiClass builderClass = ApplicationManager.getApplication().runReadAction(
                (Computable<PsiClass>) () -> JavaPsiFacade.getInstance(project).findClass(
                        "okhttp3.Request.Builder", GlobalSearchScope.allScope(project)));
        if (builderClass == null) return;

        ReferencesSearch.search(builderClass, scope).forEach(ref -> {  // outside runReadAction
            PsiNewExpression newExpr =
                    PsiTreeUtil.getParentOfType(ref.getElement(), PsiNewExpression.class);
            if (newExpr == null) return true;

            // Walk the parent chain to find .url(...), .get(), .post(...), .method(...)
            UrlResolver.ResolvedUrl resolved = new UrlResolver.ResolvedUrl(
                    UrlResolver.resolvePropertyKey("", project) != null ? "" : "[UNRESOLVED]",
                    ConfidenceLevel.LOW, "OkHttp builder URL not found");
            String httpMethod = "GET"; // default for OkHttp
            Map<String, String> headers = new HashMap<>();
            List<String> bodyFields = null;

            // Walk the enclosing method for builder chain calls
            PsiMethod callerMethod = PsiTreeUtil.getParentOfType(newExpr, PsiMethod.class);
            if (callerMethod != null && callerMethod.getBody() != null) {
                OkHttpChainVisitor visitor = new OkHttpChainVisitor(project);
                callerMethod.getBody().accept(visitor);
                if (visitor.resolvedUrl != null) resolved = visitor.resolvedUrl;
                if (visitor.httpMethod != null) httpMethod = visitor.httpMethod;
                headers = visitor.headers;
                bodyFields = visitor.bodyFields;
            }

            addResult(results, callerMethod, httpMethod, resolved,
                    headers, Map.of(), bodyFields, ClientType.OK_HTTP);
            return true;
        });
    }

    private static void scanFeignClients(Project project, GlobalSearchScope scope,
                                          List<OutboundApiCall> results) {
        PsiClass feignAnnotation = ApplicationManager.getApplication().runReadAction(
                (Computable<PsiClass>) () -> {
                    JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
                    PsiClass ann = facade.findClass(
                            "org.springframework.cloud.openfeign.FeignClient",
                            GlobalSearchScope.allScope(project));
                    if (ann == null) {
                        ann = facade.findClass("feign.RequestLine", GlobalSearchScope.allScope(project));
                    }
                    return ann;
                });
        if (feignAnnotation == null) return;

        AnnotatedElementsSearch.searchPsiClasses(feignAnnotation, scope).forEach(feignInterface -> {  // outside runReadAction
            // Extract base URL from @FeignClient(url = "...") or name
            String baseUrl = extractFeignBaseUrl(feignInterface, project);

            // Each method is a call site; its mapping annotation gives the path + verb
            for (PsiMethod method : feignInterface.getMethods()) {
                String[] verbAndPath = extractMappingVerbAndPath(method);
                if (verbAndPath == null) continue;

                String httpMethod = verbAndPath[0];
                String path = verbAndPath[1];
                String fullUrl = baseUrl + path;

                UrlResolver.ResolvedUrl resolved = new UrlResolver.ResolvedUrl(
                        fullUrl,
                        baseUrl.contains("[UNRESOLVED]") ? ConfidenceLevel.MEDIUM : ConfidenceLevel.HIGH,
                        "Feign client interface method");

                Map<String, String> headers = extractFeignHeaders(method);
                addResult(results, method, httpMethod, resolved,
                        headers, Map.of(), null, ClientType.FEIGN);
            }
            return true;
        });
    }

    private static void scanHttpUrlConnection(Project project, GlobalSearchScope scope,
                                               List<OutboundApiCall> results) {
        PsiMethod[] openConnectionMethods = ApplicationManager.getApplication().runReadAction(
                (Computable<PsiMethod[]>) () -> {
                    PsiClass urlClass = JavaPsiFacade.getInstance(project)
                            .findClass("java.net.URL", GlobalSearchScope.allScope(project));
                    return urlClass != null ? urlClass.findMethodsByName("openConnection", false)
                                           : PsiMethod.EMPTY_ARRAY;
                });

        for (PsiMethod openConnection : openConnectionMethods) {
            ReferencesSearch.search(openConnection, scope).forEach(ref -> {  // outside runReadAction
                PsiMethodCallExpression call =
                        PsiTreeUtil.getParentOfType(ref.getElement(), PsiMethodCallExpression.class);
                if (call == null) return true;

                // The URL is on the qualifier: new URL(urlExpr).openConnection()
                PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
                UrlResolver.ResolvedUrl resolved;
                if (qualifier instanceof PsiNewExpression newUrl) {
                    PsiExpression[] args = newUrl.getArgumentList() != null
                            ? newUrl.getArgumentList().getExpressions()
                            : new PsiExpression[0];
                    resolved = args.length > 0
                            ? UrlResolver.resolve(args[0], project)
                            : new UrlResolver.ResolvedUrl("[UNRESOLVED]", ConfidenceLevel.LOW,
                                    "new URL() with no arguments");
                } else {
                    resolved = new UrlResolver.ResolvedUrl("[UNRESOLVED]", ConfidenceLevel.LOW,
                            "URL source not a new URL() expression");
                }

                // Detect HTTP method from setRequestMethod call in enclosing method
                String httpMethod = "GET"; // default
                PsiMethod callerMethod =
                        PsiTreeUtil.getParentOfType(call, PsiMethod.class);
                if (callerMethod != null && callerMethod.getBody() != null) {
                    httpMethod = extractSetRequestMethod(callerMethod);
                }

                addResult(results, callerMethod, httpMethod, resolved,
                        Map.of(), Map.of(), null, ClientType.HTTP_URL_CONNECTION);
                return true;
            });
        }
    }

    private static void scanApacheHttpClient(Project project, GlobalSearchScope scope,
                                              List<OutboundApiCall> results) {
        for (Map.Entry<String, String> entry : APACHE_REQUEST_CLASSES.entrySet()) {
            String fqn = entry.getKey();
            String verb = entry.getValue();

            PsiClass requestClass = ApplicationManager.getApplication().runReadAction(
                    (Computable<PsiClass>) () -> JavaPsiFacade.getInstance(project)
                            .findClass(fqn, GlobalSearchScope.allScope(project)));
            if (requestClass == null) continue;

            ReferencesSearch.search(requestClass, scope).forEach(ref -> {  // outside runReadAction
                PsiNewExpression newExpr =
                        PsiTreeUtil.getParentOfType(ref.getElement(), PsiNewExpression.class);
                if (newExpr == null) return true;

                PsiExpression[] args = newExpr.getArgumentList() != null
                        ? newExpr.getArgumentList().getExpressions()
                        : new PsiExpression[0];

                UrlResolver.ResolvedUrl resolved = args.length > 0
                        ? UrlResolver.resolve(args[0], project)
                        : new UrlResolver.ResolvedUrl("[UNRESOLVED]", ConfidenceLevel.LOW,
                                "Apache HttpClient constructor with no URL argument");

                Map<String, String> headers = extractApacheHeaders(newExpr, project);
                PsiMethod callerMethod =
                        PsiTreeUtil.getParentOfType(newExpr, PsiMethod.class);
                addResult(results, callerMethod, verb, resolved,
                        headers, Map.of(), null, ClientType.APACHE_HTTP_CLIENT);
                return true;
            });
        }
    }

    // -------------------------------------------------------------------------
    // Helper: collect project Java files for visitor-based scans
    // -------------------------------------------------------------------------

    private static List<PsiFile> collectProjectFiles(Project project, GlobalSearchScope scope) {
        List<PsiFile> files = new ArrayList<>();
        com.intellij.psi.search.FilenameIndex
                .getAllFilesByExt(project, "java", scope)
                .forEach(vf -> {
                    com.intellij.psi.PsiFile psiFile =
                            com.intellij.psi.PsiManager.getInstance(project).findFile(vf);
                    if (psiFile != null) files.add(psiFile);
                });
        return files;
    }

    // -------------------------------------------------------------------------
    // Helper: WebClient chain extraction
    // -------------------------------------------------------------------------

    private static UrlResolver.ResolvedUrl extractWebClientUri(PsiMethodCallExpression verbCall,
                                                                 Project project) {
        // Walk the parent method call chain to find .uri(...)
        PsiElement parent = verbCall.getParent();
        int walks = 0;
        while (parent instanceof PsiMethodCallExpression parentCall && walks < 10) {
            String name = parentCall.getMethodExpression().getReferenceName();
            if ("uri".equals(name)) {
                PsiExpression[] args = parentCall.getArgumentList().getExpressions();
                if (args.length > 0) {
                    return UrlResolver.resolve(args[0], project);
                }
            }
            parent = parent.getParent();
            walks++;
        }
        return new UrlResolver.ResolvedUrl("[UNRESOLVED]", ConfidenceLevel.LOW,
                "WebClient .uri() not found in chain");
    }

    private static Map<String, String> extractWebClientHeaders(PsiMethodCallExpression start,
                                                                 Project project) {
        Map<String, String> headers = new HashMap<>();
        PsiElement parent = start.getParent();
        int walks = 0;
        while (parent instanceof PsiMethodCallExpression parentCall && walks < 10) {
            String name = parentCall.getMethodExpression().getReferenceName();
            if ("header".equals(name) || "headers".equals(name)) {
                PsiExpression[] args = parentCall.getArgumentList().getExpressions();
                if (args.length >= 2 && args[0] instanceof PsiLiteralExpression lit) {
                    Object key = lit.getValue();
                    if (key instanceof String k) {
                        headers.put(k, "[DYNAMIC]");
                    }
                }
            }
            parent = parent.getParent();
            walks++;
        }
        return headers;
    }

    private static List<String> extractWebClientBody(PsiMethodCallExpression start,
                                                       Project project) {
        PsiElement parent = start.getParent();
        int walks = 0;
        while (parent instanceof PsiMethodCallExpression parentCall && walks < 10) {
            String name = parentCall.getMethodExpression().getReferenceName();
            if ("bodyValue".equals(name) || "body".equals(name)) {
                return List.of("[DYNAMIC_BODY]");
            }
            parent = parent.getParent();
            walks++;
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Helper: OkHttp chain visitor
    // -------------------------------------------------------------------------

    private static final class OkHttpChainVisitor extends JavaRecursiveElementVisitor {
        private final Project project;
        UrlResolver.ResolvedUrl resolvedUrl;
        String httpMethod;
        Map<String, String> headers = new HashMap<>();
        List<String> bodyFields;

        OkHttpChainVisitor(Project project) {
            this.project = project;
        }

        @Override
        public void visitMethodCallExpression(PsiMethodCallExpression call) {
            super.visitMethodCallExpression(call);
            String name = call.getMethodExpression().getReferenceName();
            PsiExpression[] args = call.getArgumentList().getExpressions();

            switch (name != null ? name : "") {
                case "url" -> {
                    if (args.length > 0) resolvedUrl = UrlResolver.resolve(args[0], project);
                }
                case "get" -> httpMethod = "GET";
                case "head" -> httpMethod = "HEAD";
                case "delete" -> httpMethod = "DELETE";
                case "post" -> {
                    httpMethod = "POST";
                    if (args.length > 0) bodyFields = List.of("[DYNAMIC_BODY]");
                }
                case "put" -> {
                    httpMethod = "PUT";
                    if (args.length > 0) bodyFields = List.of("[DYNAMIC_BODY]");
                }
                case "patch" -> {
                    httpMethod = "PATCH";
                    if (args.length > 0) bodyFields = List.of("[DYNAMIC_BODY]");
                }
                case "method" -> {
                    if (args.length >= 2 && args[0] instanceof PsiLiteralExpression lit
                            && lit.getValue() instanceof String verb) {
                        httpMethod = verb.toUpperCase();
                        bodyFields = List.of("[DYNAMIC_BODY]");
                    }
                }
                case "addHeader", "header" -> {
                    if (args.length >= 2 && args[0] instanceof PsiLiteralExpression lit
                            && lit.getValue() instanceof String key) {
                        headers.put(key, "[DYNAMIC]");
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helper: Feign client
    // -------------------------------------------------------------------------

    private static String extractFeignBaseUrl(PsiClass feignInterface, Project project) {
        PsiAnnotation annotation = feignInterface.getAnnotation(
                "org.springframework.cloud.openfeign.FeignClient");
        if (annotation == null) return "";

        // Try url attribute first
        for (String attr : new String[]{"url", "value", "name"}) {
            PsiAnnotationMemberValue val = annotation.findAttributeValue(attr);
            if (val == null) continue;
            if (val instanceof PsiLiteralExpression lit && lit.getValue() instanceof String s) {
                if (s.startsWith("${") && s.endsWith("}")) {
                    // Property placeholder
                    String key = s.substring(2, s.length() - 1);
                    int colon = key.indexOf(':');
                    if (colon >= 0) key = key.substring(0, colon);
                    String resolved = UrlResolver.resolvePropertyKey(key, project);
                    return resolved != null ? resolved : s;
                }
                return s;
            }
            String text = val.getText().replace("\"", "");
            if (!text.isEmpty()) return text;
        }
        return "";
    }

    private static String[] extractMappingVerbAndPath(PsiMethod method) {
        String[][] mappings = {
                {"org.springframework.web.bind.annotation.GetMapping", "GET"},
                {"org.springframework.web.bind.annotation.PostMapping", "POST"},
                {"org.springframework.web.bind.annotation.PutMapping", "PUT"},
                {"org.springframework.web.bind.annotation.DeleteMapping", "DELETE"},
                {"org.springframework.web.bind.annotation.PatchMapping", "PATCH"},
                {"org.springframework.web.bind.annotation.RequestMapping", "GET"},
        };
        for (String[] entry : mappings) {
            PsiAnnotation ann = method.getAnnotation(entry[0]);
            if (ann == null) continue;
            PsiAnnotationMemberValue val = ann.findAttributeValue("value");
            if (val == null) val = ann.findAttributeValue("path");
            String path = val instanceof PsiLiteralExpression lit
                    && lit.getValue() instanceof String s ? s : "/";
            return new String[]{entry[1], path};
        }
        // Try feign @RequestLine("GET /path")
        PsiAnnotation requestLine = method.getAnnotation("feign.RequestLine");
        if (requestLine != null) {
            PsiAnnotationMemberValue val = requestLine.findAttributeValue("value");
            if (val instanceof PsiLiteralExpression lit && lit.getValue() instanceof String s) {
                int space = s.indexOf(' ');
                if (space > 0) {
                    return new String[]{s.substring(0, space).toUpperCase(), s.substring(space + 1)};
                }
            }
        }
        return null;
    }

    private static Map<String, String> extractFeignHeaders(PsiMethod method) {
        Map<String, String> headers = new HashMap<>();
        PsiAnnotation headersAnn = method.getAnnotation("feign.Headers");
        if (headersAnn == null) return headers;
        PsiAnnotationMemberValue val = headersAnn.findAttributeValue("value");
        if (val == null) return headers;
        // Single header or array
        String text = val.getText().replace("\"", "").replace("{", "").replace("}", "").trim();
        for (String header : text.split(",")) {
            int colon = header.indexOf(':');
            if (colon > 0) {
                headers.put(header.substring(0, colon).trim(), header.substring(colon + 1).trim());
            }
        }
        return headers;
    }

    // -------------------------------------------------------------------------
    // Helper: HttpURLConnection request method
    // -------------------------------------------------------------------------

    private static String extractSetRequestMethod(PsiMethod method) {
        String[] found = {"GET"};
        method.getBody().accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitMethodCallExpression(PsiMethodCallExpression call) {
                super.visitMethodCallExpression(call);
                if (!"setRequestMethod".equals(call.getMethodExpression().getReferenceName())) return;
                PsiExpression[] args = call.getArgumentList().getExpressions();
                if (args.length > 0 && args[0] instanceof PsiLiteralExpression lit
                        && lit.getValue() instanceof String verb) {
                    found[0] = verb.toUpperCase();
                }
            }
        });
        return found[0];
    }

    // -------------------------------------------------------------------------
    // Helper: Apache HttpClient headers
    // -------------------------------------------------------------------------

    private static Map<String, String> extractApacheHeaders(PsiNewExpression newExpr,
                                                             Project project) {
        Map<String, String> headers = new HashMap<>();
        PsiMethod callerMethod = PsiTreeUtil.getParentOfType(newExpr, PsiMethod.class);
        if (callerMethod == null || callerMethod.getBody() == null) return headers;
        callerMethod.getBody().accept(new JavaRecursiveElementVisitor() {
            @Override
            public void visitMethodCallExpression(PsiMethodCallExpression call) {
                super.visitMethodCallExpression(call);
                String name = call.getMethodExpression().getReferenceName();
                if (!"addHeader".equals(name) && !"setHeader".equals(name)) return;
                PsiExpression[] args = call.getArgumentList().getExpressions();
                if (args.length >= 2 && args[0] instanceof PsiLiteralExpression lit
                        && lit.getValue() instanceof String key) {
                    headers.put(key, "[DYNAMIC]");
                }
            }
        });
        return headers;
    }

    // -------------------------------------------------------------------------
    // Helper: exchange() HTTP method from HttpMethod enum arg
    // -------------------------------------------------------------------------

    private static String extractHttpMethodEnumArg(PsiExpression methodArg) {
        // Typically: HttpMethod.GET or HttpMethod.POST
        String text = methodArg.getText();
        int dot = text.lastIndexOf('.');
        return dot >= 0 ? text.substring(dot + 1).toUpperCase() : text.toUpperCase();
    }

    // -------------------------------------------------------------------------
    // Helper: body fields
    // -------------------------------------------------------------------------

    private static List<String> extractBodyFields(PsiExpression bodyExpr, Project project) {
        if (bodyExpr == null) return null;
        // Best-effort: return the expression text as a placeholder
        String text = bodyExpr.getText();
        if (text.isBlank()) return null;
        return List.of("[" + text + "]");
    }

    // -------------------------------------------------------------------------
    // Helper: add result
    // -------------------------------------------------------------------------

    private static void addResult(List<OutboundApiCall> results,
                                   PsiMethod callerMethod,
                                   String httpMethod,
                                   UrlResolver.ResolvedUrl resolved,
                                   Map<String, String> headers,
                                   Map<String, String> queryParams,
                                   List<String> bodyFields,
                                   ClientType clientType) {
        String callerClass = "Unknown";
        String callerMethodSig = "unknown()";
        if (callerMethod != null) {
            PsiClass cls = callerMethod.getContainingClass();
            if (cls != null && cls.getName() != null) callerClass = cls.getName();
            callerMethodSig = CallSiteAnalyzer.buildSignature(callerMethod);
        }

        results.add(new OutboundApiCall(
                callerClass,
                callerMethodSig,
                httpMethod,
                resolved.url(),
                Map.copyOf(headers),
                Map.copyOf(queryParams),
                bodyFields,
                resolved.confidence(),
                resolved.reason(),
                clientType,
                callerMethod
        ));
    }
}
