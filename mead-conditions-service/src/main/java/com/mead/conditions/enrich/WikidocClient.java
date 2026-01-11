package com.mead.conditions.enrich;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;

/**
 * Client for fetching medical content from WikiDoc (https://www.wikidoc.org/).
 * WikiDoc is a collaborative medical encyclopedia based on MediaWiki.
 * 
 * This client uses the MediaWiki API to fetch article extracts.
 * API documentation: https://www.mediawiki.org/wiki/API:Query
 */
@Component
public class WikidocClient {

    private static final Logger log = LoggerFactory.getLogger(WikidocClient.class);

    private static final String WIKIDOC_API_BASE = "https://www.wikidoc.org/api.php";
    private static final String WIKIDOC_PAGE_BASE = "https://www.wikidoc.org/index.php/";

    @Value("${mead.external.wikidoc.timeout-ms:5000}")
    private long timeoutMs;

    @Value("${mead.external.wikidoc.user-agent:MeAd/1.0}")
    private String userAgent;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final WikidocSnippetLoader fallbackLoader;

    public WikidocClient(WikidocSnippetLoader fallbackLoader) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(5000))
                .build();
        this.objectMapper = new ObjectMapper();
        this.fallbackLoader = fallbackLoader;
    }

    /**
     * Result containing WikiDoc content and source information.
     */
    public record WikidocEnrichment(
            String content,      // The article extract/summary
            String sourceUrl,    // Direct URL to the WikiDoc article
            String sourceType    // "api" if fetched live, "local" if from fallback
    ) {}

    /**
     * Fetches WikiDoc enrichment for a medical condition.
     * First tries the MediaWiki API, falls back to local .md files if API fails.
     * 
     * @param conditionId The condition identifier (slug), e.g., "asthma"
     * @param conditionName The display name, e.g., "Asthma"
     * @return WikidocEnrichment with content and source information
     */
    @Cacheable("wikidocEnrichment")
    public WikidocEnrichment getEnrichment(String conditionId, String conditionName) {
        String pageTitle = normalizeTitle(conditionName);
        String sourceUrl = WIKIDOC_PAGE_BASE + pageTitle;

        // Try to fetch from WikiDoc API
        String apiContent = fetchExtractFromApi(pageTitle);
        if (apiContent != null && !apiContent.isBlank()) {
            log.debug("WikiDoc API success for: {}", conditionName);
            return new WikidocEnrichment(apiContent, sourceUrl, "api");
        }

        // Fallback to local .md file
        String localContent = fallbackLoader.loadSnippet(conditionId);
        if (localContent != null && !localContent.isBlank()) {
            log.debug("WikiDoc fallback to local file for: {}", conditionId);
            return new WikidocEnrichment(localContent, sourceUrl, "local");
        }

        // No content available
        log.debug("No WikiDoc content found for: {}", conditionName);
        return new WikidocEnrichment(null, sourceUrl, "none");
    }

    /**
     * Fetches the article content from WikiDoc MediaWiki API.
     * Uses action=parse to get the page content as text.
     * Note: WikiDoc doesn't have TextExtracts extension, so we use action=parse instead.
     */
    private String fetchExtractFromApi(String pageTitle) {
        try {
            String encodedTitle = URLEncoder.encode(pageTitle, StandardCharsets.UTF_8);
            
            // Build MediaWiki API URL using action=parse
            // This returns the parsed HTML of the page, which we then convert to plain text
            // API docs: https://www.mediawiki.org/wiki/API:Parsing_wikitext
            String url = WIKIDOC_API_BASE + "?" +
                    "action=parse" +
                    "&page=" + encodedTitle +
                    "&prop=text" +              // Get the parsed HTML text
                    "&section=0" +              // Only the intro section (before first heading)
                    "&disabletoc=true" +        // No table of contents
                    "&format=json";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("WikiDoc API returned status {}", response.statusCode());
                return null;
            }

            return parseContentFromResponse(response.body());

        } catch (Exception e) {
            log.warn("WikiDoc API request failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Parses the MediaWiki API JSON response to extract the article content.
     * Converts HTML to plain text by stripping tags.
     */
    private String parseContentFromResponse(String jsonResponse) {
        try {
            JsonNode root = objectMapper.readTree(jsonResponse);
            
            // Check for errors (page not found, etc.)
            if (root.has("error")) {
                String errorCode = root.path("error").path("code").asText();
                log.debug("WikiDoc API error: {}", errorCode);
                return null;
            }
            
            // Get the HTML text from parse result
            String htmlContent = root.path("parse").path("text").path("*").asText();
            
            if (htmlContent == null || htmlContent.isBlank()) {
                return null;
            }
            
            // Convert HTML to plain text
            String plainText = htmlToPlainText(htmlContent);
            
            if (plainText != null && !plainText.isBlank()) {
                return truncateToReasonableLength(plainText);
            }

        } catch (Exception e) {
            log.warn("Failed to parse WikiDoc API response: {}", e.getMessage());
        }

        return null;
    }
    
    /**
     * Converts HTML to plain text by removing tags and cleaning up whitespace.
     */
    private static String htmlToPlainText(String html) {
        if (html == null) return null;
        
        // Remove script and style elements completely
        String text = html.replaceAll("<script[^>]*>.*?</script>", "");
        text = text.replaceAll("<style[^>]*>.*?</style>", "");
        
        // Remove HTML comments
        text = text.replaceAll("<!--.*?-->", "");
        
        // Replace <br> and </p> with newlines
        text = text.replaceAll("<br\\s*/?>", "\n");
        text = text.replaceAll("</p>", "\n\n");
        
        // Remove all remaining HTML tags
        text = text.replaceAll("<[^>]+>", "");
        
        // Decode common HTML entities
        text = text.replace("&nbsp;", " ");
        text = text.replace("&amp;", "&");
        text = text.replace("&lt;", "<");
        text = text.replace("&gt;", ">");
        text = text.replace("&quot;", "\"");
        text = text.replace("&#39;", "'");
        
        // Clean up whitespace
        text = text.replaceAll("[ \\t]+", " ");           // Multiple spaces to single
        text = text.replaceAll("\\n\\s*\\n+", "\n\n");    // Multiple newlines to double
        text = text.trim();
        
        return text;
    }

    /**
     * Normalizes condition name to WikiDoc page title format.
     * WikiDoc uses title case with underscores.
     */
    private static String normalizeTitle(String name) {
        if (name == null) return "";
        // Replace spaces with underscores, capitalize first letter of each word
        return name.trim()
                .replace(" ", "_")
                .replace("-", "_");
    }

    /**
     * Truncates extract to a reasonable length for display.
     * WikiDoc extracts can be very long; we want a student-friendly summary.
     */
    private static String truncateToReasonableLength(String text) {
        if (text == null) return null;
        
        int maxLength = 2000; // Characters
        if (text.length() <= maxLength) {
            return text.trim();
        }
        
        // Find a good break point (end of sentence)
        int breakPoint = text.lastIndexOf(". ", maxLength);
        if (breakPoint > maxLength / 2) {
            return text.substring(0, breakPoint + 1).trim();
        }
        
        return text.substring(0, maxLength).trim() + "...";
    }
}
