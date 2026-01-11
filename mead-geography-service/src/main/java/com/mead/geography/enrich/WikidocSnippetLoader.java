package com.mead.geography.enrich;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;

import static java.nio.charset.StandardCharsets.UTF_8;

@Component
public class WikidocSnippetLoader {

    private static final Logger log = LoggerFactory.getLogger(WikidocSnippetLoader.class);

    public String loadSnippet(String regionId) {
        String snippetPath = "wikidoc/" + regionId + ".md";
        try {
            ClassPathResource resource = new ClassPathResource(snippetPath);
            if (!resource.exists()) {
                log.debug("Snippet not found for region: {}", regionId);
                return null;
            }
            try (InputStream inputStream = resource.getInputStream()) {
                byte[] contentBytes = inputStream.readAllBytes();
                return new String(contentBytes, UTF_8).trim();
            }
        } catch (Exception e) {
            log.error("Error loading snippet for region {}: {}", regionId, e.getMessage());
            return null;
        }
    }
}
