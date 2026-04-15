package com.repoinspector.agent.config;

import com.repoinspector.agent.server.RepoBuddyAgentServer;
import com.repoinspector.agent.service.RepoExecutionService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot auto-configuration for the RepoBuddy execution agent.
 *
 * <p>Starts a lightweight, standalone HTTP server on a dedicated port
 * (default {@value RepoBuddyAgentServer#DEFAULT_PORT}, configurable via
 * {@code repoinspector.agent.port} in {@code application.properties}).
 *
 * <p>This server runs completely outside Tomcat and Spring Security — no
 * authentication filter, JWT validator, or OAuth2 resource-server can
 * interfere with it.
 */
@AutoConfiguration
@ConditionalOnWebApplication
public class RepoExecutionAutoConfiguration {

    @Bean
    public RepoExecutionService repoExecutionService(ApplicationContext context) {
        return new RepoExecutionService(context);
    }

    @Bean
    public RepoBuddyAgentServer repoBuddyAgentServer(RepoExecutionService service) {
        // Port is chosen by the OS (port 0) — no configuration needed.
        return new RepoBuddyAgentServer(service);
    }

    // -------------------------------------------------------------------------
    // Hibernate SQL capture
    // -------------------------------------------------------------------------

    @Configuration
    @ConditionalOnClass(name = "org.hibernate.resource.jdbc.spi.StatementInspector")
    static class HibernateConfig {

        private static final String INTERCEPTOR_PROPERTY =
                "hibernate.session_factory.statement_inspector";
        private static final String INTERCEPTOR_CLASS =
                "com.repoinspector.agent.sql.SqlCapturingInterceptor";

        @Bean
        public HibernatePropertiesCustomizer sqlCapturingCustomizer() {
            return properties -> properties.put(INTERCEPTOR_PROPERTY, INTERCEPTOR_CLASS);
        }
    }
}
