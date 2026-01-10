package com.mead.conditions.enrich;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import static com.mead.conditions.config.AsyncConfig.MEAD_EXECUTOR;

@Component
public class WikidataClient {

    private static final String WD = "http://www.wikidata.org/entity/";
    private static final String WDT = "http://www.wikidata.org/prop/direct/";
    private static final String SCHEMA = "http://schema.org/";
    private static final String WIKIBASE = "http://wikiba.se/ontology#";
    private static final String BD = "http://www.bigdata.com/rdf#";

    private static final String LANG_EN = "en";
    private static final int LIMIT_DESCRIPTION = 1;
    private static final int LIMIT_LIST = 30;
    private static final int LIMIT_IMAGES = 50;

    @Value("${mead.external.wikidata.endpoint}")
    private String endpoint;

    @Value("${mead.external.wikidata.user-agent}")
    private String userAgent;

    @Value("${mead.external.wikidata.timeout-ms:8000}")
    private long timeoutMs;

    private final SparqlHttpClient sparql;
    private final Executor meadExecutor;

    public WikidataClient(SparqlHttpClient sparql,
                          @Qualifier(MEAD_EXECUTOR) Executor meadExecutor) {
        this.sparql = sparql;
        this.meadExecutor = meadExecutor;
    }

    public record WikidataEnrichment(
            String description,
            List<String> symptoms,
            List<String> riskFactors,
            List<String> images
    ) {}

    @Cacheable("wikidataEnrichment")
    public WikidataEnrichment enrichFromEntityUri(String wikidataEntityUri) {
        String entityUriId = wikidataEntityUri.substring(wikidataEntityUri.lastIndexOf('/') + 1);

        CompletableFuture<String> descriptionFuture = future(() -> fetchDescription(entityUriId));
        CompletableFuture<List<String>> symptomsFuture = future(() -> fetchSymptoms(entityUriId));
        CompletableFuture<List<String>> riskFactorsFuture = future(() -> fetchRiskFactors(entityUriId));
        CompletableFuture<List<String>> imagesFuture = future(() -> fetchImageUrls(entityUriId));

        CompletableFuture.allOf(descriptionFuture, symptomsFuture, riskFactorsFuture, imagesFuture).join();

        return new WikidataEnrichment(
                descriptionFuture.join(),
                symptomsFuture.join(),
                riskFactorsFuture.join(),
                imagesFuture.join()
        );
    }

    private String fetchDescription(String entityUriId) {
        String sparqlQuery = """
                PREFIX wd: <%s>
                PREFIX schema: <%s>
                SELECT ?desc WHERE {
                  wd:%s schema:description ?desc .
                  FILTER(LANG(?desc) = "%s")
                } LIMIT %d
                """.formatted(WD, SCHEMA, entityUriId, LANG_EN, LIMIT_DESCRIPTION);

        return sparql.selectFirstString(request(sparqlQuery, "desc"));
    }

    private List<String> fetchSymptoms(String entityUriId) {
        String sparqlQuery = """
                PREFIX wd: <%s>
                PREFIX wdt: <%s>
                PREFIX wikibase: <%s>
                PREFIX bd: <%s>

                SELECT DISTINCT ?symptomLabel WHERE {
                  wd:%s wdt:P780 ?symptom .
                  SERVICE wikibase:label { bd:serviceParam wikibase:language "%s". }
                } LIMIT %d
                """.formatted(WD, WDT, WIKIBASE, BD, entityUriId, LANG_EN, LIMIT_LIST);

        return sparql.selectStrings(request(sparqlQuery, "symptomLabel"));
    }

    private List<String> fetchRiskFactors(String entityUriId) {
        String sparqlQuery = """
                PREFIX wd: <%s>
                PREFIX wdt: <%s>
                PREFIX wikibase: <%s>
                PREFIX bd: <%s>

                SELECT DISTINCT ?rfLabel WHERE {
                  wd:%s wdt:P5642 ?rf .
                  SERVICE wikibase:label { bd:serviceParam wikibase:language "%s". }
                } LIMIT %d
                """.formatted(WD, WDT, WIKIBASE, BD, entityUriId, LANG_EN, LIMIT_LIST);

        return sparql.selectStrings(request(sparqlQuery, "rfLabel"));
    }

    private List<String> fetchImageUrls(String entityUriId) {
        String sparqlQuery = """
                PREFIX wd: <%s>
                PREFIX wdt: <%s>
                SELECT ?img WHERE {
                  wd:%s wdt:P18 ?img .
                } LIMIT %d
                """.formatted(WD, WDT, entityUriId, LIMIT_IMAGES);

        List<String> raw = sparql.selectStrings(request(sparqlQuery, "img"));
        return normalizeWikidataImages(raw);
    }

    private SparqlHttpClient.SelectRequest request(String sparqlQuery, String varName) {
        return new SparqlHttpClient.SelectRequest(
                endpoint,
                timeoutMs,
                Map.of(SparqlHttpClient.HEADER_USER_AGENT, userAgent),
                sparqlQuery,
                varName,
                "Wikidata"
        );
    }

    private <T> CompletableFuture<T> future(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, meadExecutor);
    }

    private static final String COMMONS_FILEPATH = "https://commons.wikimedia.org/wiki/Special:FilePath/";
    private static final String COMMONS_HTTPS_URL = "https://commons.wikimedia.org/";
    private static final String COMMONS_HTTP_URL = "http://commons.wikimedia.org/";

    private static List<String> normalizeWikidataImages(List<String> rawImages) {
        if (rawImages == null || rawImages.isEmpty()) {
            return List.of();
        }

        List<String> normalized = rawImages.stream()
                .map(WikidataClient::normalizeWikidataP18ToCommonsUrl)
                .filter(s -> s != null && !s.isBlank())
                .toList();

        return removeDuplicates(normalized);
    }

    private static String normalizeWikidataP18ToCommonsUrl(String wikidataImageValue) {
        if (wikidataImageValue == null || wikidataImageValue.isBlank()) {
            return null;
        }

        int idx = wikidataImageValue.lastIndexOf("Special:FilePath/");
        if (idx >= 0) {
            String tail = wikidataImageValue.substring(idx + "Special:FilePath/".length());
            return COMMONS_FILEPATH + tail;
        }

        if (wikidataImageValue.startsWith(COMMONS_HTTP_URL)) {
            return COMMONS_HTTPS_URL + wikidataImageValue.substring(COMMONS_HTTP_URL.length());
        }
        if (wikidataImageValue.startsWith(COMMONS_HTTPS_URL)) {
            return wikidataImageValue;
        }

        String filename = wikidataImageValue.startsWith("File:") ? wikidataImageValue.substring("File:".length()) : wikidataImageValue;
        filename = filename.replace(" ", "_");
        return COMMONS_FILEPATH + URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static List<String> removeDuplicates(List<String> inputList) {
        Map<String, String> map = new java.util.LinkedHashMap<>();
        inputList.forEach(s -> map.putIfAbsent(s.toLowerCase(), s));
        return List.copyOf(map.values());
    }
}
