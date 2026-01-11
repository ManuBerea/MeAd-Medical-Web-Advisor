package com.mead.geography.enrich;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class WikidocSnippetLoader {

    private static final Logger log = LoggerFactory.getLogger(WikidocSnippetLoader.class);

    private static final Pattern PARAGRAPH_PATTERN = Pattern.compile("<p>(.*?)</p>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    @Value("${mead.external.wikidoc.api-url:https://www.wikidoc.org/api.php}")
    private String apiUrl;

    @Value("${mead.external.wikidoc.timeout-ms:8000}")
    private long timeoutMs;

    @Value("${mead.external.wikidoc.user-agent:MeAd/0.0.1}")
    private String userAgent;

    private HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    void init() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();
    }

    @Cacheable("wikidocRegionSnippet")
    public String loadSnippet(String regionId) {
        return loadSnippet(regionId, null);
    }

    @Cacheable("wikidocRegionSnippet")
    public String loadSnippet(String regionId, String regionName) {
        List<String> candidates = buildCandidates(regionId, regionName);
        for (String candidate : candidates) {
            String html = fetchPageHtml(candidate);
            List<String> paragraphs = extractParagraphs(html);
            if (!paragraphs.isEmpty()) {
                return paragraphs.get(0);
            }
        }
        return null;
    }

    private List<String> buildCandidates(String regionId, String regionName) {
        Set<String> candidates = new LinkedHashSet<>();
        String nameCandidate = normalizePageBase(regionName);
        if (nameCandidate != null) candidates.add(nameCandidate);
        String idCandidate = normalizePageBase(regionId);
        if (idCandidate != null) candidates.add(idCandidate);
        return new ArrayList<>(candidates);
    }

    private String normalizePageBase(String value) {
        if (value == null) return null;
        String trimmed = value.trim().replace('_', ' ').replace('-', ' ');
        if (trimmed.isBlank()) return null;
        boolean hasUppercase = trimmed.chars().anyMatch(Character::isUpperCase);
        String normalized = hasUppercase ? trimmed : titleCase(trimmed);
        return normalized.replace(' ', '_');
    }

    private String titleCase(String input) {
        String[] parts = input.split("\\s+");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (builder.length() > 0) builder.append(' ');
            builder.append(part.substring(0, 1).toUpperCase())
                    .append(part.substring(1));
        }
        return builder.toString();
    }

    private String fetchPageHtml(String pageTitle) {
        if (pageTitle == null) return null;
        String url = buildApiUrl(pageTitle);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("User-Agent", userAgent)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.debug("WikiDoc response {} for {}", response.statusCode(), pageTitle);
                return null;
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode textNode = root.path("parse").path("text").path("*");
            return textNode.isMissingNode() ? null : textNode.asText();
        } catch (Exception e) {
            log.warn("WikiDoc request failed for {}: {}", pageTitle, e.getMessage());
            return null;
        }
    }

    private String buildApiUrl(String pageTitle) {
        String encodedTitle = URLEncoder.encode(pageTitle, StandardCharsets.UTF_8);
        String base = apiUrl.endsWith("?") ? apiUrl : apiUrl + "?";
        return base + "action=parse&page=" + encodedTitle + "&prop=text&format=json";
    }

    private List<String> extractParagraphs(String html) {
        if (html == null || html.isBlank()) return List.of();
        List<String> paragraphs = new ArrayList<>();
        Matcher matcher = PARAGRAPH_PATTERN.matcher(html);
        while (matcher.find()) {
            String cleaned = cleanHtml(matcher.group(1));
            if (cleaned != null && cleaned.length() > 40 && !isPlaceholder(cleaned)) {
                paragraphs.add(cleaned);
            }
        }
        return paragraphs;
    }

    private String cleanHtml(String html) {
        if (html == null) return null;
        String text = html.replaceAll("<[^>]+>", " ");
        text = decodeEntities(text);
        text = text.replaceAll("\\[[0-9]+\\]", "");
        text = text.replaceAll("\\s+", " ").trim();
        return text;
    }

    private String decodeEntities(String text) {
        return text.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
    }

    private boolean isPlaceholder(String text) {
        String lower = text.toLowerCase();
        return lower.contains("there is currently no text in this page")
                || lower.contains("may refer to");
    }
}
