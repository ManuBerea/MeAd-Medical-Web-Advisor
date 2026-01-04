package com.mead.conditions.enrich;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class WikidocSnippetLoader {

    public String loadSnippet(String conditionId) {
        String path = "wikidoc/" + conditionId + ".md";
        try {
            var res = new ClassPathResource(path);
            if (!res.exists()) return null;
            try (var in = res.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
        } catch (Exception e) {
            return null;
        }
    }
}