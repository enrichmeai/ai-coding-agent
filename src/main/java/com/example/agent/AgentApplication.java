package com.example.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Map;

/**
 * Entry point for the AI coding agent.
 *
 * Run: ./gradlew bootRun
 *      (or) java -jar build/libs/ai-coding-agent-0.1.0.jar
 *
 * The storage backend is selected before Spring auto-config runs:
 *   - memory  (default / unknown): exclude JPA + DataSource auto-config so
 *     Hibernate doesn't try to start without a datasource.
 *   - sqlite : activate the storage-sqlite profile (datasource + Flyway).
 *   - postgres: activate the storage-postgres profile (datasource + Flyway).
 */
@SpringBootApplication
public class AgentApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(AgentApplication.class);

        String storage = resolve("AGENT_STORAGE_TYPE", "agent.storage.type", "memory", args);
        switch (storage == null ? "memory" : storage.toLowerCase()) {
            case "sqlite" -> app.setAdditionalProfiles("storage-sqlite");
            case "postgres" -> app.setAdditionalProfiles("storage-postgres");
            default -> app.setDefaultProperties(Map.of(
                    "spring.autoconfigure.exclude",
                    "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
                    "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
                    "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration," +
                    "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration"
            ));
        }

        app.run(args);
    }

    /** Read a config value from CLI (`--key=value`), env var, system property, or default. */
    private static String resolve(String envName, String propName, String def, String[] args) {
        String cliPrefix = "--" + propName + "=";
        for (String a : args) if (a.startsWith(cliPrefix)) return a.substring(cliPrefix.length());
        String fromEnv = System.getenv(envName);
        if (fromEnv != null && !fromEnv.isBlank()) return fromEnv;
        String fromProp = System.getProperty(propName);
        if (fromProp != null && !fromProp.isBlank()) return fromProp;
        return def;
    }
}
