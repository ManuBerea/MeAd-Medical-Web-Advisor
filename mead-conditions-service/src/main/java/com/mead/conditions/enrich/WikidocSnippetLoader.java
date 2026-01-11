package com.mead.conditions.enrich;

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
    private static final Pattern LIST_ITEM_PATTERN = Pattern.compile("<li[^>]*>(.*?)</li>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern STYLE_BLOCK_PATTERN = Pattern.compile("<style[^>]*>.*?</style>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern SCRIPT_BLOCK_PATTERN = Pattern.compile("<script[^>]*>.*?</script>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TABLE_BLOCK_PATTERN = Pattern.compile("<table[^>]*>.*?</table>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern REFERENCE_BLOCK_PATTERN = Pattern.compile("<ol[^>]*class=\"references\"[^>]*>.*?</ol>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern REFLIST_BLOCK_PATTERN = Pattern.compile("<div[^>]*class=\"reflist\"[^>]*>.*?</div>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern SUP_REFERENCE_PATTERN = Pattern.compile("<sup[^>]*class=\"reference\"[^>]*>.*?</sup>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TOC_BLOCK_PATTERN = Pattern.compile("<div[^>]*(id|class)=\"[^\"]*toc[^\"]*\"[^>]*>.*?</div>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern NUMERIC_ENTITY_PATTERN = Pattern.compile("&#(\\d+);");
    private static final Pattern HEX_ENTITY_PATTERN = Pattern.compile("&#x([0-9a-fA-F]+);");

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

    @Cacheable("wikidocOverview")
    public String fetchOverview(String conditionId, String conditionName) {
        String baseTitle = resolveBaseTitle(conditionId, conditionName);
        String pageTitle = buildSuffixedTitle(baseTitle, "overview");
        String overview = extractOverviewFromPage(pageTitle);
        if (overview != null) return overview;
        return extractOverviewFromPage(baseTitle);
    }

    @Cacheable("wikidocCauses")
    public List<String> fetchCauses(String conditionId, String conditionName) {
        String baseTitle = resolveBaseTitle(conditionId, conditionName);
        String pageTitle = buildSuffixedTitle(baseTitle, "causes");
        List<String> items = extractListFromSection(pageTitle, List.of("Causes", "Etiology", "Overview"));
        if (!items.isEmpty()) return items;
        return extractListFromSection(baseTitle, List.of("Causes", "Etiology"));
    }

    @Cacheable("wikidocRiskFactors")
    public List<String> fetchRiskFactors(String conditionId, String conditionName) {
        String baseTitle = resolveBaseTitle(conditionId, conditionName);
        String pageTitle = buildSuffixedTitle(baseTitle, "risk_factors");
        List<String> items = extractListFromSection(pageTitle, List.of("Risk factors", "Risk Factors", "Overview"));
        if (!items.isEmpty()) return items;
        return extractListFromSection(baseTitle, List.of("Risk factors", "Risk Factors"));
    }

    @Cacheable("wikidocSymptoms")
    public List<String> fetchSymptoms(String conditionId, String conditionName) {
        String pageTitle = resolveBaseTitle(conditionId, conditionName);
        return extractListFromSection(pageTitle, List.of("Signs and symptoms", "Symptoms"));
    }

    private List<String> extractListFromSection(String pageTitle, List<String> headings) {
        String html = fetchSectionHtml(pageTitle, headings);
        if (html == null) return List.of();
        List<String> items = extractListItems(html);
        if (!items.isEmpty()) {
            return items;
        }
        return extractInlineItemsFromParagraphs(html);
    }

    private String buildPageTitle(String conditionId, String conditionName, String suffix) {
        String base = conditionName != null && !conditionName.isBlank()
                ? conditionName
                : conditionId;
        if (base == null || base.isBlank()) return null;
        String normalized = normalizePageBase(base);
        if (normalized == null) return null;
        return normalized + "_" + suffix;
    }

    private String buildPageBase(String conditionId, String conditionName) {
        String base = conditionName != null && !conditionName.isBlank()
                ? conditionName
                : conditionId;
        if (base == null || base.isBlank()) return null;
        return normalizePageBase(base);
    }

    private String normalizePageBase(String value) {
        String trimmed = value.trim().replace('_', ' ').replace('-', ' ');
        if (trimmed.isBlank()) return null;
        boolean hasUppercase = trimmed.chars().anyMatch(Character::isUpperCase);
        String normalized = hasUppercase ? trimmed : titleCase(trimmed);
        return normalized.replace(' ', '_');
    }

    private String buildSuffixedTitle(String baseTitle, String suffix) {
        if (baseTitle == null || baseTitle.isBlank()) return null;
        return baseTitle + "_" + suffix;
    }

    public String resolveBaseTitle(String conditionId, String conditionName) {
        String base = buildPageBase(conditionId, conditionName);
        if (base == null) return null;
        String resolved = resolveRedirect(base);
        return resolved != null ? resolved : base;
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

    private String fetchSectionHtml(String pageTitle, List<String> headings) {
        String sectionIndex = fetchSectionIndex(pageTitle, headings);
        if (sectionIndex == null) return null;
        return fetchPageHtml(pageTitle, sectionIndex);
    }

    private String fetchSectionIndex(String pageTitle, List<String> headings) {
        if (pageTitle == null) return null;
        String url = buildApiUrl(pageTitle, "sections", null);
        try {
            String response = sendRequest(url);
            if (response == null) return null;
            JsonNode root = objectMapper.readTree(response);
            JsonNode sections = root.path("parse").path("sections");
            if (!sections.isArray()) return null;
            for (JsonNode section : sections) {
                String line = section.path("line").asText("");
                String index = section.path("index").asText("");
                if (line.isEmpty() || index.isEmpty()) continue;
                if (matchesHeading(line, headings)) return index;
            }
        } catch (Exception e) {
            log.debug("WikiDoc section lookup failed for {}: {}", pageTitle, e.getMessage());
        }
        return null;
    }

    private boolean matchesHeading(String line, List<String> headings) {
        String normalized = line.trim().toLowerCase();
        return headings.stream().anyMatch(h -> {
            String target = h.toLowerCase();
            return normalized.equals(target) || normalized.contains(target);
        });
    }

    private String fetchPageHtml(String pageTitle) {
        return fetchPageHtml(pageTitle, null);
    }

    private String fetchPageHtml(String pageTitle, String sectionIndex) {
        if (pageTitle == null) return null;
        String url = buildApiUrl(pageTitle, "text", sectionIndex);
        try {
            String response = sendRequest(url);
            if (response == null) return null;
            JsonNode root = objectMapper.readTree(response);
            JsonNode textNode = root.path("parse").path("text").path("*");
            return textNode.isMissingNode() ? null : textNode.asText();
        } catch (Exception e) {
            log.debug("WikiDoc parse failed for {}: {}", pageTitle, e.getMessage());
            return null;
        }
    }

    private String resolveRedirect(String pageTitle) {
        if (pageTitle == null) return null;
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
            log.debug("WikiDoc redirect lookup failed for {}: {}", pageTitle, e.getMessage());
        }
        return null;
    }

    private String buildApiUrl(String pageTitle, String prop, String sectionIndex) {
        String encodedTitle = URLEncoder.encode(pageTitle, StandardCharsets.UTF_8);
        String base = apiUrl.endsWith("?") ? apiUrl : apiUrl + "?";
        StringBuilder builder = new StringBuilder(base)
                .append("action=parse")
                .append("&page=").append(encodedTitle)
                .append("&prop=").append(prop)
                .append("&format=json");
        if (sectionIndex != null) {
            builder.append("&section=").append(URLEncoder.encode(sectionIndex, StandardCharsets.UTF_8));
        }
        return builder.toString();
    }

    private String buildQueryUrl(String pageTitle) {
        String encodedTitle = URLEncoder.encode(pageTitle, StandardCharsets.UTF_8);
        String base = apiUrl.endsWith("?") ? apiUrl : apiUrl + "?";
        return new StringBuilder(base)
                .append("action=query")
                .append("&titles=").append(encodedTitle)
                .append("&redirects=1")
                .append("&format=json")
                .toString();
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
                log.debug("WikiDoc response {} for {}", response.statusCode(), url);
                return null;
            }
            return response.body();
        } catch (Exception e) {
            log.warn("WikiDoc request failed: {}", e.getMessage());
            return null;
        }
    }

    private List<String> extractParagraphs(String html) {
        if (html == null || html.isBlank()) return List.of();
        String sanitized = stripNoiseBlocks(html);
        List<String> paragraphs = new ArrayList<>();
        Matcher matcher = PARAGRAPH_PATTERN.matcher(sanitized);
        while (matcher.find()) {
            String cleaned = cleanHtml(matcher.group(1));
            String normalized = normalizeItem(cleaned, 200);
            if (normalized != null && normalized.length() > 40 && !isPlaceholder(normalized)) {
                paragraphs.add(normalized);
            }
        }
        return paragraphs;
    }

    private List<String> extractListItems(String html) {
        if (html == null || html.isBlank()) return List.of();
        String sanitized = stripNoiseBlocks(html);
        Set<String> items = new LinkedHashSet<>();
        Matcher matcher = LIST_ITEM_PATTERN.matcher(sanitized);
        while (matcher.find()) {
            String cleaned = cleanHtml(matcher.group(1));
            String normalized = normalizeItem(cleaned, 140);
            if (normalized != null && normalized.length() > 3 && !isPlaceholder(normalized)) {
                items.add(normalized);
            }
        }
        return new ArrayList<>(items);
    }

    private String extractOverviewFromPage(String pageTitle) {
        if (pageTitle == null) return null;
        String html = fetchSectionHtml(pageTitle, List.of("Overview"));
        if (html == null) {
            html = fetchPageHtml(pageTitle);
        }
        List<String> paragraphs = extractOverviewParagraphs(html);
        return paragraphs.isEmpty() ? null : String.join("\n", paragraphs);
    }

    private List<String> extractOverviewParagraphs(String html) {
        if (html == null || html.isBlank()) return List.of();
        String sanitized = stripNoiseBlocks(html);
        List<String> paragraphs = new ArrayList<>();
        int totalLength = 0;
        Matcher matcher = PARAGRAPH_PATTERN.matcher(sanitized);
        while (matcher.find()) {
            String cleaned = cleanHtml(matcher.group(1));
            String normalized = normalizeParagraph(cleaned, 2000);
            if (normalized != null && normalized.length() > 40 && !isPlaceholder(normalized)) {
                if (paragraphs.size() >= 5 || totalLength + normalized.length() > 3500) {
                    break;
                }
                paragraphs.add(normalized);
                totalLength += normalized.length();
            }
        }
        return paragraphs;
    }

    private String cleanHtml(String html) {
        if (html == null) return null;
        String text = html.replaceAll("<[^>]+>", " ");
        text = decodeEntities(text);
        text = text.replaceAll("\\[[^\\]]*\\]", "");
        text = text.replaceAll("[\\u2020\\u2021\\uFFFD]", "");
        text = text.replace('\u00A0', ' ');
        text = text.replaceAll("\\s+", " ").trim();
        return text;
    }

    private String decodeEntities(String text) {
        String decoded = text.replace("&amp;", "&")
                .replace("&nbsp;", " ")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&ndash;", "-")
                .replace("&mdash;", "-");
        return decodeNumericEntities(decoded);
    }

    private boolean isPlaceholder(String text) {
        String lower = text.toLowerCase();
        return lower.contains("there is currently no text in this page")
                || lower.contains("may refer to");
    }

    private String stripNoiseBlocks(String html) {
        String stripped = STYLE_BLOCK_PATTERN.matcher(html).replaceAll("");
        stripped = SCRIPT_BLOCK_PATTERN.matcher(stripped).replaceAll("");
        stripped = TABLE_BLOCK_PATTERN.matcher(stripped).replaceAll("");
        stripped = REFERENCE_BLOCK_PATTERN.matcher(stripped).replaceAll("");
        stripped = REFLIST_BLOCK_PATTERN.matcher(stripped).replaceAll("");
        stripped = SUP_REFERENCE_PATTERN.matcher(stripped).replaceAll("");
        stripped = TOC_BLOCK_PATTERN.matcher(stripped).replaceAll("");
        return stripped;
    }

    private String decodeNumericEntities(String text) {
        String result = text;
        Matcher matcher = NUMERIC_ENTITY_PATTERN.matcher(result);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            int codePoint = Integer.parseInt(matcher.group(1));
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(new String(Character.toChars(codePoint))));
        }
        matcher.appendTail(buffer);
        result = buffer.toString();

        matcher = HEX_ENTITY_PATTERN.matcher(result);
        buffer = new StringBuffer();
        while (matcher.find()) {
            int codePoint = Integer.parseInt(matcher.group(1), 16);
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(new String(Character.toChars(codePoint))));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private String normalizeItem(String text, int maxLength) {
        if (text == null) return null;
        String trimmed = text.replaceAll("^[^\\p{L}\\p{N}]+", "").trim();
        if (trimmed.isBlank()) return null;
        if (trimmed.matches(".*\\d.*")) return null;
        if (shouldSkip(trimmed)) return null;

        String candidate = shortenItem(trimmed);
        candidate = candidate.replaceAll("\\s*[\\.;:,]+$", "");
        candidate = candidate.replaceAll("\\s+", " ").trim();
        if (candidate.isBlank()) return null;
        if (candidate.length() > maxLength) return null;
        return candidate;
    }

    private String normalizeParagraph(String text, int maxLength) {
        if (text == null) return null;
        String trimmed = text.replaceAll("^[^\\p{L}\\p{N}]+", "").trim();
        if (trimmed.isBlank()) return null;
        if (shouldSkip(trimmed)) return null;
        String normalized = trimmed.replaceAll("\\s+", " ").trim();
        if (normalized.length() > maxLength) {
            normalized = normalized.substring(0, maxLength).trim();
        }
        return normalized;
    }

    private String shortenItem(String text) {
        String candidate = text;
        if (candidate.contains(":") && candidate.length() > 80) {
            candidate = candidate.split(":", 2)[0];
        }
        if (candidate.length() > 120) {
            candidate = candidate.split("\\.\\s+", 2)[0];
        }
        if (candidate.length() > 120) {
            candidate = candidate.split(";", 2)[0];
        }
        return candidate.trim();
    }

    private boolean shouldSkip(String text) {
        String lower = text.toLowerCase();
        return lower.contains("mw-parser-output")
                || lower.contains("citation")
                || lower.contains("cs1-")
                || lower.contains("pmid")
                || lower.contains("doi")
                || lower.contains("isbn")
                || lower.contains("wikimedia")
                || lower.contains("http://")
                || lower.contains("https://")
                || lower.contains("accessed")
                || lower.startsWith("↑");
    }

    private List<String> extractInlineItemsFromParagraphs(String html) {
        if (html == null || html.isBlank()) return List.of();
        String sanitized = stripNoiseBlocks(html);
        Set<String> items = new LinkedHashSet<>();
        Matcher matcher = PARAGRAPH_PATTERN.matcher(sanitized);
        while (matcher.find()) {
            String cleaned = cleanHtml(matcher.group(1));
            if (cleaned == null || cleaned.isBlank()) continue;
            int includeIndex = findIncludeIndex(cleaned);
            if (includeIndex < 0) continue;
            String tail = cleaned.substring(includeIndex).trim();
            tail = tail.replaceAll("^[^\\p{L}\\p{N}]+", "").trim();
            tail = tail.replaceAll("\\.$", "");
            for (String token : tail.split("[,;]")) {
                String normalized = normalizeItem(token.replaceAll("^and\\s+", ""), 90);
                if (normalized != null && normalized.length() > 2) {
                    items.add(normalized);
                }
            }
        }
        return new ArrayList<>(items);
    }

    private int findIncludeIndex(String text) {
        String lower = text.toLowerCase();
        int index = lower.indexOf("include");
        if (index < 0) return -1;
        if (lower.startsWith("including", index)) {
            return Math.min(index + "including".length(), text.length());
        }
        if (lower.startsWith("includes", index)) {
            return Math.min(index + "includes".length(), text.length());
        }
        return Math.min(index + "include".length(), text.length());
    }
}
