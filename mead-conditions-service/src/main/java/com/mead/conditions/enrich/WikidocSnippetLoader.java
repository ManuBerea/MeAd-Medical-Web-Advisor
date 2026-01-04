package com.mead.conditions.enrich;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;

import static java.nio.charset.StandardCharsets.UTF_8;

@Component
public class WikidocSnippetLoader {

    private static final Logger log = LoggerFactory.getLogger(WikidocSnippetLoader.class);

    public String loadSnippet(String conditionId) {
        String path = "wikidoc/" + conditionId + ".md";
        try {
            ClassPathResource resource = new ClassPathResource(path);
            if (!resource.exists()) {
                log.debug("Snippet not found for condition: {}", conditionId);
                return null;
            }
            try (InputStream inputStream = resource.getInputStream()) {
                return new String(inputStream.readAllBytes(), UTF_8).trim();
            }
        } catch (Exception e) {
            log.error("Error loading snippet for condition {}: {}", conditionId, e.getMessage());
            return null;
        }
    }
}