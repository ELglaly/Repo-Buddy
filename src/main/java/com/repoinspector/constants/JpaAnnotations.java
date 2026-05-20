package com.repoinspector.constants;

/**
 * Single source of truth for JPA / Hibernate annotation FQNs used by the
 * inspections. Both the {@code jakarta.*} and legacy {@code javax.*} namespaces
 * are listed because projects on Spring Boot 2.x still use {@code javax}.
 */
public final class JpaAnnotations {

    private JpaAnnotations() {}

    public static final String[] ENTITY_FQNS = {
            "jakarta.persistence.Entity",
            "javax.persistence.Entity"
    };

    public static final String[] ASSOCIATION_FQNS = {
            "jakarta.persistence.OneToMany",  "javax.persistence.OneToMany",
            "jakarta.persistence.ManyToMany", "javax.persistence.ManyToMany",
            "jakarta.persistence.ManyToOne",  "javax.persistence.ManyToOne",
            "jakarta.persistence.OneToOne",   "javax.persistence.OneToOne"
    };

    public static final String BATCH_SIZE = "org.hibernate.annotations.BatchSize";

    /** Simple annotation name from an FQN, e.g. {@code OneToMany}. */
    public static String simpleName(String fqn) {
        return fqn.substring(fqn.lastIndexOf('.') + 1);
    }
}
