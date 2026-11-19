package com.papazone.ai.rag;

import javax.sql.DataSource;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Objects;
import org.springframework.core.env.Environment;

@Configuration
@ConditionalOnProperty(prefix = "rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RagDatabaseConfig {

    @Bean
    @ConfigurationProperties("rag.datasource")
    public DataSourceProperties ragDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = "ragDataSource")
    public DataSource ragDataSource(
            @Qualifier("ragDataSourceProperties") DataSourceProperties properties,
            Environment environment) {

        String configuredPassword = properties.getPassword();
        String environmentPassword = environment.getProperty("RAG_DB_PASSWORD");

        System.out.println("========== RAG DATASOURCE DIAGNOSTICS ==========");
        System.out.println("RAG JDBC URL: " + properties.getUrl());
        System.out.println("RAG DB Username: " + properties.getUsername());
        System.out.println("RAG Password Present: "
                + (configuredPassword != null && !configuredPassword.isEmpty()));
        System.out.println("RAG Password Matches Environment: "
                + Objects.equals(configuredPassword, environmentPassword));
        System.out.println("================================================");

        return properties.initializeDataSourceBuilder().build();
    }

    @Bean(name = "ragJdbcTemplate")
    public JdbcTemplate ragJdbcTemplate(@Qualifier("ragDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    public VectorStore ragVectorStore(
            @Qualifier("ragJdbcTemplate") JdbcTemplate jdbcTemplate,
            EmbeddingModel embeddingModel,
            RagProperties properties
    ) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .dimensions(properties.embeddingDimensions())
                .initializeSchema(true)
                .build();
    }
}
