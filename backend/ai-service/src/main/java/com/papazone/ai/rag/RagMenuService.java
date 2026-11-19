package com.papazone.ai.rag;

import com.papazone.ai.client.MenuClient;
import com.papazone.ai.model.MenuEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@ConditionalOnProperty(prefix = "rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RagMenuService {
    private static final Logger log = LoggerFactory.getLogger(RagMenuService.class);

    private final MenuClient menuClient;
    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final RagProperties properties;

    public RagMenuService(MenuClient menuClient, VectorStore vectorStore,
                          @Qualifier("ragJdbcTemplate") JdbcTemplate jdbcTemplate,
                          RagProperties properties) {
        this.menuClient = menuClient;
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    /** Re-indexes current menu records. Existing vectors for each menu ID are removed first. */
    public int indexMenu() {
        List<MenuEntry> items = menuClient.allItems();
        List<Document> documents = new ArrayList<>();
        for (MenuEntry item : items) {
            String content = "Food: " + safe(item.name())
                    + "\nDescription: " + safe(item.description())
                    + "\nCategory: " + safe(item.category())
                    + "\nPrice: " + item.price()
                    + "\nAvailability: " + (item.available() ? "Available" : "Unavailable");
            documents.add(new Document(content, Map.of(
                    "menuItemId", String.valueOf(item.id()),
                    "name", safe(item.name()),
                    "category", safe(item.category()),
                    "price", String.valueOf(item.price()),
                    "available", String.valueOf(item.available())
            )));
        }
        for (MenuEntry item : items) {
            jdbcTemplate.update("DELETE FROM vector_store WHERE metadata->>'menuItemId' = ?", String.valueOf(item.id()));
        }
        if (!documents.isEmpty()) vectorStore.add(documents);
        log.info("RAG menu indexing completed: {} records", documents.size());
        return documents.size();
    }

    public List<Document> search(String question) {
        return vectorStore.similaritySearch(SearchRequest.builder()
                .query(question)
                .topK(properties.topK())
                .build());
    }

    public String context(String question) {
        try {
            List<Document> docs = search(question);
            return docs.stream().map(Document::getText).collect(Collectors.joining("\n\n"));
        } catch (Exception ex) {
            log.warn("RAG retrieval unavailable; continuing with existing menu context: {}", ex.getMessage());
            return "";
        }
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
