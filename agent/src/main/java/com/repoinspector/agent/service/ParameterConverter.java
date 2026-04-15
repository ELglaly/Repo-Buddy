package com.repoinspector.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoinspector.agent.dto.ExecutionRequest.ParameterValue;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.List;

/**
 * Converts string parameter values from the plugin UI into proper Java objects
 * matching the target repository method's parameter types.
 *
 * <p>Primitive wrappers and {@code String} are handled with direct parsing.
 * Enums are matched by name (case-insensitive).  All other types are
 * deserialized from JSON using the provided {@link ObjectMapper}.
 */
final class ParameterConverter {

    private final ObjectMapper objectMapper;

    ParameterConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Converts each {@link ParameterValue} in {@code paramValues} to the
     * corresponding Java type declared by {@code method}.
     *
     * @param method     the target repository method
     * @param paramValues raw string values from the plugin UI, one per parameter
     * @return array of converted arguments ready for {@link Method#invoke}
     */
    Object[] convert(Method method, List<ParameterValue> paramValues) throws Exception {
        Parameter[] params = method.getParameters();
        Object[] args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            String raw = i < paramValues.size() ? paramValues.get(i).value() : null;
            args[i] = convertSingle(params[i].getType(), raw);
        }
        return args;
    }

    private Object convertSingle(Class<?> type, String value) throws Exception {
        if (value == null || value.isEmpty()) return null;
        if (type == String.class) return value;
        if (type == Long.class    || type == long.class)    return Long.parseLong(value.trim());
        if (type == Integer.class || type == int.class)     return Integer.parseInt(value.trim());
        if (type == Short.class   || type == short.class)   return Short.parseShort(value.trim());
        if (type == Byte.class    || type == byte.class)    return Byte.parseByte(value.trim());
        if (type == Double.class  || type == double.class)  return Double.parseDouble(value.trim());
        if (type == Float.class   || type == float.class)   return Float.parseFloat(value.trim());
        if (type == Boolean.class || type == boolean.class) return Boolean.parseBoolean(value.trim());
        if (type.isEnum()) return matchEnum(type, value.trim());
        return objectMapper.readValue(value, type);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object matchEnum(Class<?> enumType, String value) {
        for (Object constant : enumType.getEnumConstants()) {
            if (((Enum) constant).name().equalsIgnoreCase(value)) return constant;
        }
        throw new IllegalArgumentException("No enum constant " + enumType.getSimpleName() + "." + value);
    }
}
