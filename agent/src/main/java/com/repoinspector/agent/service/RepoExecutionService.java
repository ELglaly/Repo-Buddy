package com.repoinspector.agent.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.repoinspector.agent.dto.ExecutionRequest;
import com.repoinspector.agent.dto.ExecutionResult;
import com.repoinspector.agent.sql.SqlLogStore;
import org.springframework.context.ApplicationContext;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;

/**
 * Core execution engine: resolves the repository bean from the Spring
 * {@link ApplicationContext}, converts parameters, invokes the method via
 * reflection, and returns a structured {@link ExecutionResult}.
 *
 * <p>SQL statements captured by {@link com.repoinspector.agent.sql.SqlCapturingInterceptor}
 * during the invocation are included in the result.
 */
public class RepoExecutionService {

    private final ApplicationContext context;
    private final ParameterConverter converter;
    private final ObjectMapper objectMapper;

    public RepoExecutionService(ApplicationContext context) {
        this.context = context;
        this.objectMapper = buildObjectMapper();
        this.converter = new ParameterConverter(objectMapper);
    }

    /**
     * Executes the repository method described by {@code request}.
     *
     * @param request method invocation descriptor from the IDE plugin
     * @return structured result including JSON output, SQL log, and timing
     */
    public ExecutionResult execute(ExecutionRequest request) {
        long start = System.currentTimeMillis();
        SqlLogStore.clear();

        try {
            Class<?> repoInterface = Class.forName(request.repositoryClass());
            Object bean = context.getBean(repoInterface);

            Method method = resolveMethod(repoInterface, request.methodName(),
                    request.parameters().size());
            Object[] args = converter.convert(method, request.parameters());

            Object raw = method.invoke(bean, args);
            Object unwrapped = raw instanceof Optional<?> opt ? opt.orElse(null) : raw;

            String json = safeSerialize(unwrapped);

            return new ExecutionResult(
                    "SUCCESS", json,
                    SqlLogStore.snapshot(),
                    System.currentTimeMillis() - start,
                    null
            );
        } catch (Exception e) {
            return new ExecutionResult(
                    "FAILURE", null,
                    SqlLogStore.snapshot(),
                    System.currentTimeMillis() - start,
                    stackTraceOf(e)
            );
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Method resolveMethod(Class<?> clazz, String name, int paramCount)
            throws NoSuchMethodException {
        // getMethods() includes inherited methods (findById, save, etc. from JpaRepository)
        return Arrays.stream(clazz.getMethods())
                .filter(m -> m.getName().equals(name) && m.getParameterCount() == paramCount)
                .findFirst()
                .orElseThrow(() -> new NoSuchMethodException(
                        clazz.getName() + "#" + name + "(" + paramCount + " params)"));
    }

    private String safeSerialize(Object value) {
        if (value == null) return "null";
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            // Lazy-loading or circular-reference issue — fall back to toString
            return "\"[Serialization failed: " + e.getMessage().replace("\"", "'") + "]\"";
        }
    }

    private static String stackTraceOf(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static ObjectMapper buildObjectMapper() {
        ObjectMapper mapper = new ObjectMapper()
                .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .enable(SerializationFeature.INDENT_OUTPUT);
        // Auto-register any Jackson modules on the classpath (e.g., JavaTimeModule, Hibernate6Module)
        mapper.findAndRegisterModules();
        return mapper;
    }
}
