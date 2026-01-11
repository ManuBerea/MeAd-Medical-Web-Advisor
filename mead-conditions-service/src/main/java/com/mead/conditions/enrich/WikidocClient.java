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
     * Includes multiple sections from the WikiDoc article.
     */
    public record WikidocEnrichment(
            String overview,           // Main overview/introduction
            String causes,             // Causes / Etiology
            String pathophysiology,    // How the disease affects the body
            String diagnosis,          // Diagnostic methods
            String treatment,          // Treatment options
            String prevention,         // Prevention measures
            String prognosis,          // Expected outcomes
            String epidemiology,       // Population statistics
            String sourceUrl,          // Direct URL to the WikiDoc article
            String sourceType          // "api" if fetched live, "local" if from fallback
    ) {
        // Helper for backward compatibility - returns overview as main content
        public String content() {
            return overview;
        }
    }

    /**
     * Fetches WikiDoc enrichment for a medical condition.
     * First tries the MediaWiki API to fetch multiple sections, falls back to local .md files if API fails.
     * 
     * @param conditionId The condition identifier (slug), e.g., "asthma"
     * @param conditionName The display name, e.g., "Asthma"
     * @return WikidocEnrichment with content and source information
     */
    @Cacheable("wikidocEnrichment")
    public WikidocEnrichment getEnrichment(String conditionId, String conditionName) {
        String pageTitle = normalizeTitle(conditionName);
        String sourceUrl = WIKIDOC_PAGE_BASE + pageTitle;

        // Try to fetch multiple sections from WikiDoc API
        WikidocEnrichment apiResult = fetchMultipleSections(pageTitle, sourceUrl);
        if (apiResult != null && apiResult.overview() != null) {
            log.debug("WikiDoc API success for: {}", conditionName);
            return apiResult;
        }

        // Fallback to local .md file (only provides overview)
        String localContent = fallbackLoader.loadSnippet(conditionId);
        if (localContent != null && !localContent.isBlank()) {
            log.debug("WikiDoc fallback to local file for: {}", conditionId);
            return new WikidocEnrichment(
                    localContent, null, null, null, null, null, null, null,
                    sourceUrl, "local"
            );
        }

        // No content available
        log.debug("No WikiDoc content found for: {}", conditionName);
        return new WikidocEnrichment(
                null, null, null, null, null, null, null, null,
                sourceUrl, "none"
        );
    }
    
    /**
     * Fetches multiple sections from WikiDoc article.
     * Uses section=0 for intro, then searches for common medical sections.
     */
    private WikidocEnrichment fetchMultipleSections(String pageTitle, String sourceUrl) {
        try {
            // Fetch the full article and extract sections
            String fullContent = fetchFullArticle(pageTitle);
            if (fullContent == null) {
                return null;
            }
            
            // Parse sections from the content
            String overview = extractSection(fullContent, null); // Intro before first heading
            String causes = extractSection(fullContent, "Causes", "Etiology", "Cause");
            String pathophysiology = extractSection(fullContent, "Pathophysiology", "Pathogenesis");
            String diagnosis = extractSection(fullContent, "Diagnosis", "Differential Diagnosis", "Diagnostic");
            String treatment = extractSection(fullContent, "Treatment", "Therapy", "Management");
            String prevention = extractSection(fullContent, "Prevention", "Prophylaxis");
            String prognosis = extractSection(fullContent, "Prognosis", "Outcome");
            String epidemiology = extractSection(fullContent, "Epidemiology", "Demographics");
            
            return new WikidocEnrichment(
                    overview, causes, pathophysiology, diagnosis, treatment, 
                    prevention, prognosis, epidemiology,
                    sourceUrl, "api"
            );
            
        } catch (Exception e) {
            log.warn("Failed to fetch multiple sections for {}: {}", pageTitle, e.getMessage());
            return null;
        }
    }
    
    /**
     * Fetches the full article HTML from WikiDoc.
     */
    private String fetchFullArticle(String pageTitle) {
        try {
            String encodedTitle = URLEncoder.encode(pageTitle, StandardCharsets.UTF_8);
            
            String url = WIKIDOC_API_BASE + "?" +
                    "action=parse" +
                    "&page=" + encodedTitle +
                    "&prop=text" +
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
                return null;
            }

            JsonNode root = objectMapper.readTree(response.body());
            if (root.has("error")) {
                return null;
            }
            
            return root.path("parse").path("text").path("*").asText();
            
        } catch (Exception e) {
            log.warn("Failed to fetch full article: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Extracts a section from HTML content by heading name.
     * If headingNames is null/empty, extracts content before first heading (intro).
     */
    private String extractSection(String html, String... headingNames) {
        if (html == null) return null;
        
        // For intro (no heading specified), get content before first <h2> or <h3>
        if (headingNames == null || headingNames.length == 0 || headingNames[0] == null) {
            int firstHeading = html.indexOf("<h2");
            if (firstHeading == -1) firstHeading = html.indexOf("<h3");
            if (firstHeading == -1) firstHeading = html.length();
            
            String intro = html.substring(0, firstHeading);
            return truncateToReasonableLength(htmlToPlainText(intro));
        }
        
        // Search for any of the heading names
        for (String headingName : headingNames) {
            if (headingName == null) continue;
            
            // Look for <h2>, <h3>, or <span class="mw-headline"> containing the heading
            String[] patterns = {
                    "<h2[^>]*>" + headingName,
                    "<h3[^>]*>" + headingName,
                    "id=\"" + headingName.replace(" ", "_") + "\"",
                    ">" + headingName + "</span>",
                    ">" + headingName + "</a>"
            };
            
            int sectionStart = -1;
            for (String pattern : patterns) {
                int idx = html.toLowerCase().indexOf(pattern.toLowerCase());
                if (idx != -1) {
                    sectionStart = idx;
                    break;
                }
            }
            
            if (sectionStart != -1) {
                // Find the end of this section (next h2 or h3)
                int contentStart = html.indexOf(">", sectionStart);
                if (contentStart == -1) continue;
                contentStart++; // Move past the >
                
                // Find next heading
                String remaining = html.substring(contentStart);
                int nextH2 = remaining.indexOf("<h2");
                int nextH3 = remaining.indexOf("<h3");
                
                int sectionEnd;
                if (nextH2 == -1 && nextH3 == -1) {
                    sectionEnd = remaining.length();
                } else if (nextH2 == -1) {
                    sectionEnd = nextH3;
                } else if (nextH3 == -1) {
                    sectionEnd = nextH2;
                } else {
                    sectionEnd = Math.min(nextH2, nextH3);
                }
                
                String sectionHtml = remaining.substring(0, sectionEnd);
                String plainText = htmlToPlainText(sectionHtml);
                
                if (plainText != null && !plainText.isBlank() && plainText.length() > 20) {
                    return truncateToReasonableLength(plainText);
                }
            }
        }
        
        return null;
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
     * Converts HTML to plain text by removing tags, wiki markup, and cleaning up.
     */
    private static String htmlToPlainText(String html) {
        if (html == null) return null;
        
        // Remove script, style, and reference elements completely
        String text = html.replaceAll("(?is)<script[^>]*>.*?</script>", "");
        text = text.replaceAll("(?is)<style[^>]*>.*?</style>", "");
        text = text.replaceAll("(?is)<sup[^>]*class=\"[^\"]*reference[^\"]*\"[^>]*>.*?</sup>", ""); // Remove reference superscripts
        
        // Remove edit links [edit], [edit source], [edit | edit source]
        text = text.replaceAll("(?is)<span[^>]*class=\"[^\"]*mw-editsection[^\"]*\"[^>]*>.*?</span>", "");
        
        // Remove HTML comments
        text = text.replaceAll("<!--.*?-->", "");
        
        // Replace <br> and </p> with newlines
        text = text.replaceAll("<br\\s*/?>", "\n");
        text = text.replaceAll("</p>", "\n\n");
        text = text.replaceAll("</li>", "\n");
        
        // Remove all remaining HTML tags
        text = text.replaceAll("<[^>]+>", "");
        
        // Decode numeric HTML entities (&#91; = [, &#93; = ], etc.)
        text = decodeHtmlEntities(text);
        
        // Remove Wikipedia/WikiDoc specific markup that leaked through
        text = text.replaceAll("\\[edit\\]", "");
        text = text.replaceAll("\\[edit \\| edit source\\]", "");
        text = text.replaceAll("\\[edit source\\]", "");
        text = text.replaceAll("\\[citation needed\\]", "");
        text = text.replaceAll("\\[clarification needed\\]", "");
        text = text.replaceAll("\\[dubious[^\\]]*\\]", "");
        text = text.replaceAll("\\[note \\d+\\]", "");
        text = text.replaceAll("\\[\\d+\\]", ""); // Remove reference numbers like [1], [18], etc.
        text = text.replaceAll("Further information:[^\\n]*", ""); // Remove "Further information:" lines
        
        // Clean up whitespace
        text = text.replaceAll("[ \\t]+", " ");           // Multiple spaces to single
        text = text.replaceAll(" ?\n ?", "\n");           // Trim spaces around newlines
        text = text.replaceAll("\\n{3,}", "\n\n");        // Max 2 consecutive newlines
        text = text.trim();
        
        return text;
    }
    
    /**
     * Decodes HTML entities including numeric ones (&#91; -> [)
     */
    private static String decodeHtmlEntities(String text) {
        if (text == null) return null;
        
        // Named entities
        text = text.replace("&nbsp;", " ");
        text = text.replace("&amp;", "&");
        text = text.replace("&lt;", "<");
        text = text.replace("&gt;", ">");
        text = text.replace("&quot;", "\"");
        text = text.replace("&apos;", "'");
        text = text.replace("&ndash;", "–");
        text = text.replace("&mdash;", "—");
        text = text.replace("&lsquo;", "'");
        text = text.replace("&rsquo;", "'");
        text = text.replace("&ldquo;", "\"");
        text = text.replace("&rdquo;", "\"");
        text = text.replace("&hellip;", "...");
        text = text.replace("&bull;", "•");
        text = text.replace("&copy;", "©");
        text = text.replace("&reg;", "®");
        text = text.replace("&trade;", "™");
        text = text.replace("&deg;", "°");
        text = text.replace("&plusmn;", "±");
        text = text.replace("&times;", "×");
        text = text.replace("&divide;", "÷");
        text = text.replace("&frac12;", "½");
        text = text.replace("&frac14;", "¼");
        text = text.replace("&frac34;", "¾");
        
        // Decode numeric entities (&#91; -> [, &#93; -> ], etc.)
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("&#(\\d+);");
        java.util.regex.Matcher matcher = pattern.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            int charCode = Integer.parseInt(matcher.group(1));
            matcher.appendReplacement(sb, String.valueOf((char) charCode));
        }
        matcher.appendTail(sb);
        text = sb.toString();
        
        // Decode hex entities (&#x5B; -> [)
        pattern = java.util.regex.Pattern.compile("&#x([0-9A-Fa-f]+);");
        matcher = pattern.matcher(text);
        sb = new StringBuffer();
        while (matcher.find()) {
            int charCode = Integer.parseInt(matcher.group(1), 16);
            matcher.appendReplacement(sb, String.valueOf((char) charCode));
        }
        matcher.appendTail(sb);
        
        return sb.toString();
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
