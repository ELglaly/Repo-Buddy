package com.repoinspector.analysis;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiBinaryExpression;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiJavaToken;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiPolyadicExpression;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiVariable;
import com.intellij.psi.util.PsiModificationTracker;
import com.repoinspector.model.OutboundApiCall.ConfidenceLevel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.io.StringReader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a PSI expression representing a URL into a plain string.
 *
 * <p>All public methods must be called inside a PSI read action — the caller is responsible.
 *
 * <p>Resolution strategy:
 * <ul>
 *   <li>String literals → returned directly (HIGH confidence)</li>
 *   <li>References to constants/fields → followed to their initializers (max depth 3)</li>
 *   <li>{@code @Value("${key}")} fields → looked up in {@code application.properties} /
 *       {@code application.yml}</li>
 *   <li>String concatenation ({@code +}) → each operand resolved and joined</li>
 *   <li>{@code String.format(fmt, args)} → {@code %s}/{@code %d} replaced with placeholder tokens</li>
 *   <li>{@code UriComponentsBuilder} chain → path segments extracted from the call chain</li>
 *   <li>Anything else → {@code [UNRESOLVED]} (LOW confidence)</li>
 * </ul>
 */
public final class UrlResolver {

    private static final int MAX_DEPTH = 3;
    private static final String UNRESOLVED = "[UNRESOLVED]";

    // Project-level properties cache, invalidated by PSI modification count
    private record PropertiesEntry(long modCount, Map<String, String> props) {}
    private static final ConcurrentHashMap<String, PropertiesEntry> PROPS_CACHE =
            new ConcurrentHashMap<>();

    private UrlResolver() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Attempts to resolve {@code expression} to a URL string.
     *
     * @param expression PSI expression representing the URL argument
     * @param project    the current project
     * @return a {@link ResolvedUrl} carrying the best-effort string and confidence
     */
    public static ResolvedUrl resolve(PsiExpression expression, Project project) {
        return resolve(expression, project, 0);
    }

    /**
     * Looks up a Spring {@code @Value} property key (e.g. {@code "some.base.url"}) in the
     * project's resource files.
     *
     * @return the resolved value string, or {@code null} if the key was not found
     */
    public static String resolvePropertyKey(String key, Project project) {
        Map<String, String> props = loadAllProperties(project);
        return props.get(key);
    }

    // -------------------------------------------------------------------------
    // Core resolution (recursive)
    // -------------------------------------------------------------------------

    private static ResolvedUrl resolve(PsiExpression expression, Project project, int depth) {
        if (expression == null || depth > MAX_DEPTH) {
            return new ResolvedUrl(UNRESOLVED, ConfidenceLevel.LOW,
                    "Expression null or max resolution depth reached");
        }

        if (expression instanceof PsiLiteralExpression literal) {
            return fromLiteral(literal);
        }

        if (expression instanceof PsiReferenceExpression ref) {
            return fromReference(ref, project, depth);
        }

        // PsiPolyadicExpression covers multi-operand + (a + b + c)
        if (expression instanceof PsiPolyadicExpression poly) {
            return fromPolyadic(poly, project, depth);
        }

        if (expression instanceof PsiBinaryExpression binary) {
            return fromBinary(binary, project, depth);
        }

        if (expression instanceof PsiMethodCallExpression call) {
            return fromMethodCall(call, project, depth);
        }

        return new ResolvedUrl(UNRESOLVED, ConfidenceLevel.LOW,
                "Unsupported expression type: " + expression.getClass().getSimpleName());
    }

    // -------------------------------------------------------------------------
    // Per-node handlers
    // -------------------------------------------------------------------------

    private static ResolvedUrl fromLiteral(PsiLiteralExpression literal) {
        Object value = literal.getValue();
        if (value instanceof String s) {
            return new ResolvedUrl(s, ConfidenceLevel.HIGH, "String literal");
        }
        return new ResolvedUrl(UNRESOLVED, ConfidenceLevel.LOW, "Non-string literal");
    }

    private static ResolvedUrl fromReference(PsiReferenceExpression ref, Project project, int depth) {
        PsiElement resolved = ref.resolve();

        if (resolved instanceof PsiVariable variable) {
            // Check for @Value annotation on fields
            if (resolved instanceof PsiField field) {
                String valueKey = extractValueAnnotationKey(field);
                if (valueKey != null) {
                    String propValue = resolvePropertyKey(valueKey, project);
                    if (propValue != null) {
                        return new ResolvedUrl(propValue, ConfidenceLevel.HIGH,
                                "@Value(\"${" + valueKey + "}\") resolved from application.properties");
                    } else {
                        return new ResolvedUrl("${" + valueKey + "}", ConfidenceLevel.MEDIUM,
                                "@Value(\"${" + valueKey + "}\") key not found in resource files");
                    }
                }
            }

            // Follow the initializer expression
            PsiExpression initializer = variable.getInitializer();
            if (initializer != null) {
                return resolve(initializer, project, depth + 1);
            }
        }

        return new ResolvedUrl(UNRESOLVED, ConfidenceLevel.LOW,
                "Reference could not be resolved to a variable with an initializer");
    }

    private static ResolvedUrl fromPolyadic(PsiPolyadicExpression poly, Project project, int depth) {
        // Only handle string concatenation (+)
        PsiJavaToken token = poly.getTokenBeforeOperand(poly.getOperands()[1]);
        if (token == null || !"+".equals(token.getText())) {
            return new ResolvedUrl(UNRESOLVED, ConfidenceLevel.LOW,
                    "Non-concatenation polyadic expression");
        }

        StringBuilder sb = new StringBuilder();
        ConfidenceLevel lowestConfidence = ConfidenceLevel.HIGH;

        for (PsiExpression operand : poly.getOperands()) {
            ResolvedUrl part = resolve(operand, project, depth + 1);
            sb.append(part.url());
            if (part.confidence().ordinal() > lowestConfidence.ordinal()) {
                lowestConfidence = part.confidence();
            }
        }

        return new ResolvedUrl(sb.toString(), lowestConfidence, "String concatenation");
    }

    private static ResolvedUrl fromBinary(PsiBinaryExpression binary, Project project, int depth) {
        PsiJavaToken sign = binary.getOperationSign();
        if (!"+".equals(sign.getText())) {
            return new ResolvedUrl(UNRESOLVED, ConfidenceLevel.LOW,
                    "Non-concatenation binary expression");
        }

        ResolvedUrl left = resolve(binary.getLOperand(), project, depth + 1);
        ResolvedUrl right = resolve(binary.getROperand(), project, depth + 1);

        ConfidenceLevel combined = left.confidence().ordinal() >= right.confidence().ordinal()
                ? left.confidence() : right.confidence();

        return new ResolvedUrl(left.url() + right.url(), combined, "Binary string concatenation");
    }

    private static ResolvedUrl fromMethodCall(PsiMethodCallExpression call, Project project, int depth) {
        String methodName = call.getMethodExpression().getReferenceName();
        if (methodName == null) {
            return new ResolvedUrl(UNRESOLVED, ConfidenceLevel.LOW, "Unknown method call");
        }

        // String.format(fmt, args...)
        if ("format".equals(methodName)) {
            return resolveStringFormat(call, project, depth);
        }

        // UriComponentsBuilder chain
        if ("fromHttpUrl".equals(methodName) || "fromUriString".equals(methodName)
                || "newInstance".equals(methodName)) {
            return resolveUriComponentsBuilder(call, project, depth);
        }

        // toUriString / build / toString ending a builder chain
        if ("toUriString".equals(methodName) || "toUri".equals(methodName)) {
            PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
            if (qualifier instanceof PsiMethodCallExpression qualifierCall) {
                return resolveUriBuilderChain(qualifierCall, project, depth);
            }
        }

        return new ResolvedUrl(UNRESOLVED, ConfidenceLevel.LOW,
                "Unrecognized method call: " + methodName + "()");
    }

    // -------------------------------------------------------------------------
    // String.format resolution
    // -------------------------------------------------------------------------

    private static ResolvedUrl resolveStringFormat(PsiMethodCallExpression call,
                                                    Project project, int depth) {
        PsiExpression[] args = call.getArgumentList().getExpressions();
        if (args.length == 0) {
            return new ResolvedUrl(UNRESOLVED, ConfidenceLevel.LOW, "String.format with no args");
        }

        ResolvedUrl fmt = resolve(args[0], project, depth + 1);
        if (fmt.confidence() == ConfidenceLevel.LOW) {
            return fmt;
        }

        // Replace %s, %d, %f, %n with positional placeholders
        String result = fmt.url();
        int argIndex = 1;
        // Simple sequential replacement
        while (result.contains("%s") || result.contains("%d") || result.contains("%f")) {
            String placeholder = argIndex < args.length
                    ? "{arg" + (argIndex - 1) + "}"
                    : "{arg?}";
            result = result.replaceFirst("%[sdf]", placeholder);
            argIndex++;
        }

        return new ResolvedUrl(result, ConfidenceLevel.MEDIUM,
                "String.format — dynamic args replaced with placeholders");
    }

    // -------------------------------------------------------------------------
    // UriComponentsBuilder chain resolution
    // -------------------------------------------------------------------------

    private static ResolvedUrl resolveUriComponentsBuilder(PsiMethodCallExpression entryCall,
                                                            Project project, int depth) {
        // Walk the chain upward (each call's qualifier is the previous builder)
        // Collect: fromHttpUrl(base), .path(segment), .queryParam(k, v)
        return resolveUriBuilderChain(entryCall, project, depth);
    }

    private static ResolvedUrl resolveUriBuilderChain(PsiMethodCallExpression call,
                                                       Project project, int depth) {
        // Collect all calls in the chain bottom-up
        StringBuilder urlBuilder = new StringBuilder();
        Map<String, String> queryParams = new HashMap<>();
        ConfidenceLevel lowestConfidence = ConfidenceLevel.HIGH;
        int chainWalks = 0;
        final int MAX_CHAIN = 10;

        PsiMethodCallExpression current = call;
        while (current != null && chainWalks < MAX_CHAIN) {
            String name = current.getMethodExpression().getReferenceName();
            PsiExpression[] args = current.getArgumentList().getExpressions();

            if ("fromHttpUrl".equals(name) || "fromUriString".equals(name)) {
                if (args.length > 0) {
                    ResolvedUrl base = resolve(args[0], project, depth + 1);
                    urlBuilder.insert(0, base.url());
                    if (base.confidence().ordinal() > lowestConfidence.ordinal()) {
                        lowestConfidence = base.confidence();
                    }
                }
                break; // this is the root of the chain
            } else if ("path".equals(name) || "pathSegment".equals(name)) {
                if (args.length > 0) {
                    ResolvedUrl seg = resolve(args[0], project, depth + 1);
                    urlBuilder.insert(0, seg.url());
                    if (seg.confidence().ordinal() > lowestConfidence.ordinal()) {
                        lowestConfidence = seg.confidence();
                    }
                }
            } else if ("queryParam".equals(name)) {
                if (args.length >= 2) {
                    ResolvedUrl key = resolve(args[0], project, depth + 1);
                    queryParams.put(key.url(), "[DYNAMIC]");
                }
            }
            // Advance to qualifier (previous call in chain)
            PsiExpression qualifier = current.getMethodExpression().getQualifierExpression();
            current = qualifier instanceof PsiMethodCallExpression qCall ? qCall : null;
            chainWalks++;
        }

        String url = urlBuilder.toString();
        if (url.isEmpty()) {
            return new ResolvedUrl(UNRESOLVED, ConfidenceLevel.LOW,
                    "UriComponentsBuilder chain could not be resolved");
        }
        return new ResolvedUrl(url, lowestConfidence,
                "UriComponentsBuilder chain resolved" + (queryParams.isEmpty() ? "" :
                        " with query params: " + queryParams.keySet()));
    }

    // -------------------------------------------------------------------------
    // @Value annotation extraction
    // -------------------------------------------------------------------------

    /**
     * If {@code field} has {@code @Value("${key}")} or {@code @Value("${key:default}")},
     * returns the property key string. Otherwise returns {@code null}.
     */
    static String extractValueAnnotationKey(PsiField field) {
        var annotation = field.getAnnotation("org.springframework.beans.factory.annotation.Value");
        if (annotation == null) return null;

        var memberValue = annotation.findAttributeValue("value");
        if (memberValue == null) return null;

        String text = memberValue.getText();
        // Typical: "${some.key}" or "${some.key:defaultValue}"
        if (text.startsWith("\"${") && text.endsWith("}\"")) {
            String inner = text.substring(3, text.length() - 2); // strip "${  and }"
            int colonIdx = inner.indexOf(':');
            return colonIdx >= 0 ? inner.substring(0, colonIdx) : inner;
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Properties file loading
    // -------------------------------------------------------------------------

    /**
     * Loads all key=value pairs from {@code application.properties} and
     * {@code application.yml} across all modules in the project.
     * Cached per project and invalidated when PSI changes.
     */
    static Map<String, String> loadAllProperties(Project project) {
        long modCount = PsiModificationTracker.getInstance(project).getModificationCount();
        String cacheKey = project.getName();
        PropertiesEntry entry = PROPS_CACHE.get(cacheKey);
        if (entry != null && entry.modCount() == modCount) {
            return entry.props();
        }

        Map<String, String> merged = new HashMap<>();
        for (Module module : ModuleManager.getInstance(project).getModules()) {
            for (VirtualFile sourceRoot : ModuleRootManager.getInstance(module).getSourceRoots()) {
                readPropertiesFile(sourceRoot, "application.properties", merged);
                readYamlFile(sourceRoot, "application.yml", merged);
                readYamlFile(sourceRoot, "application.yaml", merged);
            }
        }

        PROPS_CACHE.put(cacheKey, new PropertiesEntry(modCount, Map.copyOf(merged)));
        return merged;
    }

    private static void readPropertiesFile(VirtualFile root, String filename,
                                            Map<String, String> target) {
        VirtualFile file = root.findChild(filename);
        if (file == null || !file.exists()) return;
        try {
            String content = new String(file.contentsToByteArray(), StandardCharsets.UTF_8);
            Properties props = new Properties();
            props.load(new StringReader(content));
            for (String key : props.stringPropertyNames()) {
                target.put(key, props.getProperty(key));
            }
        } catch (IOException ignored) {
        }
    }

    /**
     * Minimal YAML scanner: handles {@code key: value} and two-level nested keys
     * ({@code outer:\n  inner: value} → {@code outer.inner=value}).
     * Does not require an external YAML library.
     */
    private static void readYamlFile(VirtualFile root, String filename,
                                      Map<String, String> target) {
        VirtualFile file = root.findChild(filename);
        if (file == null || !file.exists()) return;
        try {
            String content = new String(file.contentsToByteArray(), StandardCharsets.UTF_8);
            String currentParent = null;
            for (String rawLine : content.split("\n")) {
                String line = rawLine;
                if (line.trim().isEmpty() || line.trim().startsWith("#")) continue;

                int colonIdx = line.indexOf(':');
                if (colonIdx < 0) continue;

                int indent = 0;
                while (indent < line.length() && line.charAt(indent) == ' ') indent++;

                String key = line.substring(indent, colonIdx).trim();
                String value = line.substring(colonIdx + 1).trim();

                if (indent == 0) {
                    if (value.isEmpty()) {
                        // Parent key: next lines are children
                        currentParent = key;
                    } else {
                        target.put(key, value);
                        currentParent = null;
                    }
                } else if (currentParent != null && !value.isEmpty()) {
                    target.put(currentParent + "." + key, value);
                }
            }
        } catch (IOException ignored) {
        }
    }

    // -------------------------------------------------------------------------
    // Result type
    // -------------------------------------------------------------------------

    /**
     * Carries the resolved URL string alongside a confidence level and human-readable reason.
     */
    public record ResolvedUrl(String url, ConfidenceLevel confidence, String reason) {}
}
