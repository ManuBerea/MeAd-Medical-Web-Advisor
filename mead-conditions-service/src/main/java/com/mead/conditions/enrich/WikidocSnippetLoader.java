package com.mead.conditions.enrich;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;

import static java.nio.charset.StandardCharsets.UTF_8;

@Component
public class WikidocSnippetLoader {

    public String loadSnippet(String conditionId) {
        String path = "wikidoc/" + conditionId + ".md";
        try {
            ClassPathResource resource = new ClassPathResource(path);
            if (!resource.exists()) {
                return null;
            }
            try (InputStream inputStream = resource.getInputStream()) {
                return new String(inputStream.readAllBytes(), UTF_8).trim();
            }
        } catch (Exception e) {
            return null;
        }
    }
}