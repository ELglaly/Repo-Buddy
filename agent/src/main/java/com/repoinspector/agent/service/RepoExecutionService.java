package com.repoinspector.agent.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.repoinspector.agent.dto.ExecutionRequest;
import com.repoinspector.agent.dto.ExecutionResult;
import com.repoinspector.agent.sql.SqlLogStore;
import org.springframework.context.ApplicationContext;

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

    /** Keeps one execution response bounded even when a repository returns a very large value. */
    static final int MAX_SERIALIZED_RESULT_CHARS = 1_048_576;
    static final int MAX_STACK_TRACE_CAUSES = 4;
    static final int MAX_STACK_TRACE_ELEMENTS_PER_CAUSE = 48;
    static final int MAX_STACK_TRACE_CHARS = 32 * 1024;

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
        SqlLogStore.beginCapture();
        String status;
        String value;
        String exception;
        SqlLogStore.Capture capture;
        try {
            Class<?> repoInterface = Class.forName(request.repositoryClass());
            Object bean = context.getBean(repoInterface);

            Method method = resolveMethod(repoInterface, request.methodName(),
                    request.parameters().size());
            Object[] args = converter.convert(method, request.parameters());

            Object raw = method.invoke(bean, args);
            Object unwrapped = raw instanceof Optional<?> opt ? opt.orElse(null) : raw;

            String json = safeSerialize(unwrapped);

            status = "SUCCESS";
            value = json;
            exception = null;
        } catch (Exception e) {
            status = "FAILURE";
            value = null;
            exception = stackTraceOf(e);
        } finally {
            // This always removes the ThreadLocal, including exceptional execution.
            capture = SqlLogStore.finishCapture();
        }
        return new ExecutionResult(status, value, capture.entries(), System.currentTimeMillis() - start,
                exception, capture.droppedCount(), capture.overflowed());
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
            String serialized = objectMapper.writeValueAsString(value);
            if (serialized.length() <= MAX_SERIALIZED_RESULT_CHARS) return serialized;
            return "\"[Result omitted: serialized value exceeds " + MAX_SERIALIZED_RESULT_CHARS
                    + " characters]\"";
        } catch (Exception e) {
            // Lazy-loading or circular-reference issue — fall back to toString
            return "\"[Serialization failed: " + e.getMessage().replace("\"", "'") + "]\"";
        }
    }

    private static String stackTraceOf(Throwable t) {
        StringBuilder trace = new StringBuilder();
        Throwable current = t;
        for (int cause = 0; current != null && cause < MAX_STACK_TRACE_CAUSES; cause++) {
            if (cause > 0) appendBounded(trace, "Caused by: ");
            appendBounded(trace, current + "\n");
            StackTraceElement[] elements = current.getStackTrace();
            for (int i = 0; i < elements.length && i < MAX_STACK_TRACE_ELEMENTS_PER_CAUSE; i++) {
                appendBounded(trace, "\tat " + elements[i] + "\n");
            }
            if (elements.length > MAX_STACK_TRACE_ELEMENTS_PER_CAUSE) {
                appendBounded(trace, "\t... " + (elements.length - MAX_STACK_TRACE_ELEMENTS_PER_CAUSE)
                        + " more frames omitted\n");
            }
            current = current.getCause();
        }
        if (current != null) appendBounded(trace, "... additional causes omitted\n");
        return trace.toString();
    }

    private static void appendBounded(StringBuilder target, String value) {
        int remaining = MAX_STACK_TRACE_CHARS - target.length();
        if (remaining > 0) target.append(value, 0, Math.min(remaining, value.length()));
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
