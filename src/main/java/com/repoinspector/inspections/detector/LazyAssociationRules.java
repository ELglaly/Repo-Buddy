package com.repoinspector.inspections.detector;

import java.util.Set;

/**
 * Pure (PSI-free) rules for JPA lazy-association detection.
 */
public final class LazyAssociationRules {

    private LazyAssociationRules() {}

    /** Collection associations default to {@code FetchType.LAZY}. */
    private static final Set<String> COLLECTION_ASSOCIATIONS = Set.of("OneToMany", "ManyToMany");

    /** Singular associations default to {@code FetchType.EAGER}. */
    private static final Set<String> SINGULAR_ASSOCIATIONS = Set.of("ManyToOne", "OneToOne");

    /**
     * Whether an association is effectively lazy.
     *
     * @param associationSimpleName e.g. {@code OneToMany}
     * @param fetchValue            {@code "LAZY"}, {@code "EAGER"}, or {@code null} when unspecified
     */
    public static boolean isLazyAssociation(String associationSimpleName, String fetchValue) {
        if (associationSimpleName == null) return false;
        if (COLLECTION_ASSOCIATIONS.contains(associationSimpleName)) {
            return !"EAGER".equals(fetchValue);
        }
        if (SINGULAR_ASSOCIATIONS.contains(associationSimpleName)) {
            return "LAZY".equals(fetchValue);
        }
        return false;
    }

    /** JavaBeans property name for a getter, or {@code null} if not a getter. */
    public static String propertyNameFromGetter(String methodName) {
        if (methodName == null) return null;
        if (methodName.startsWith("get") && methodName.length() > 3) {
            return decapitalize(methodName.substring(3));
        }
        if (methodName.startsWith("is") && methodName.length() > 2) {
            return decapitalize(methodName.substring(2));
        }
        return null;
    }

    private static String decapitalize(String name) {
        if (name.isEmpty()) return name;
        // JavaBeans: leave acronyms like "URL" untouched when the 2nd char is also upper-case.
        if (name.length() > 1 && Character.isUpperCase(name.charAt(1))) return name;
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }
}
