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

@Component
public class WikipediaSummaryLoader {

    private static final Logger log = LoggerFactory.getLogger(WikipediaSummaryLoader.class);

    @Value("${mead.external.wikipedia.query-url:https://en.wikipedia.org/w/api.php}")
    private String queryUrl;

    @Value("${mead.external.wikipedia.summary-url:https://en.wikipedia.org/api/rest_v1/page/summary/}")
    private String summaryUrl;

    @Value("${mead.external.wikipedia.timeout-ms:8000}")
    private long timeoutMs;

    @Value("${mead.external.wikipedia.user-agent:MeAd/0.0.1}")
    private String userAgent;

    private HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostConstruct
    void init() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();
    }

    @Cacheable("wikipediaRegionSummary")
    public String loadSummary(String regionId, String regionName) {
        List<String> candidates = buildCandidates(regionId, regionName);
        for (String candidate : candidates) {
            String resolved = resolveRedirect(candidate);
            String title = resolved != null ? resolved : candidate;
            String summary = fetchSummary(title);
            if (summary != null && !summary.isBlank() && !isPlaceholder(summary)) {
                return summary.trim();
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

    private String resolveRedirect(String pageTitle) {
        String url = buildQueryUrl(pageTitle);
        try {
            String response = sendRequest(url);
            if (response == null) return null;
            JsonNode pages = objectMapper.readTree(response).path("query").path("pages");
            if (!pages.isObject()) return null;
            for (JsonNode page : pages) {
                if (page.has("missing")) continue;
                String title = page.path("title").asText(null);
                if (title == null || title.isBlank()) continue;
                return title.replace(' ', '_');
            }
        } catch (Exception e) {
            log.debug("Wikipedia redirect lookup failed for {}: {}", pageTitle, e.getMessage());
        }
        return null;
    }

    private String fetchSummary(String pageTitle) {
        if (pageTitle == null) return null;
        String url = summaryUrl + encodePath(pageTitle);
        try {
            String response = sendRequest(url);
            if (response == null) return null;
            JsonNode root = objectMapper.readTree(response);
            String type = root.path("type").asText("");
            if ("disambiguation".equalsIgnoreCase(type)) return null;
            String extract = root.path("extract").asText(null);
            if (extract == null || extract.isBlank()) return null;
            return extract.replaceAll("\\s+", " ").trim();
        } catch (Exception e) {
            log.debug("Wikipedia summary failed for {}: {}", pageTitle, e.getMessage());
            return null;
        }
    }

    private String buildQueryUrl(String pageTitle) {
        String encodedTitle = URLEncoder.encode(pageTitle, StandardCharsets.UTF_8);
        String base = queryUrl.endsWith("?") ? queryUrl : queryUrl + "?";
        return new StringBuilder(base)
                .append("action=query")
                .append("&titles=").append(encodedTitle)
                .append("&redirects=1")
                .append("&format=json")
                .toString();
    }

    private String encodePath(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String sendRequest(String url) {
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
                log.debug("Wikipedia response {} for {}", response.statusCode(), url);
                return null;
            }
            return response.body();
        } catch (Exception e) {
            log.warn("Wikipedia request failed: {}", e.getMessage());
            return null;
        }
    }

    private boolean isPlaceholder(String text) {
        String lower = text.toLowerCase();
        return lower.contains("may refer to") || lower.contains("may also refer to");
    }
}
