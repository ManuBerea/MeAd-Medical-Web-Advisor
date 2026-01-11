package com.mead.geography.enrich;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import static com.mead.geography.config.AsyncConfig.MEAD_EXECUTOR;

@Component
public class WikidataClient {

    private static final String WD = "http://www.wikidata.org/entity/";
    private static final String WDT = "http://www.wikidata.org/prop/direct/";
    private static final String SCHEMA = "http://schema.org/";
    private static final String WIKIBASE = "http://wikiba.se/ontology#";
    private static final String BD = "http://www.bigdata.com/rdf#";

    private static final String LANG_EN = "en";
    private static final int LIMIT_ONE = 1;
    private static final int LIMIT_LIST = 30;
    private static final int LIMIT_IMAGES = 10;

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
            String populationTotal,
            String populationDensity,
            List<String> climates,
            List<String> industries,
            List<String> culturalFactors,
            List<String> images
    ) {}

    @Cacheable("wikidataEnrichment")
    public WikidataEnrichment enrichFromEntityUri(String wikidataEntityUri) {
        String entityId = wikidataEntityUri.substring(wikidataEntityUri.lastIndexOf('/') + 1);

        CompletableFuture<String> descriptionFuture = executeAsync(() -> fetchDescription(entityId));
        CompletableFuture<String> populationFuture = executeAsync(() -> fetchPopulationTotal(entityId));
        CompletableFuture<String> areaFuture = executeAsync(() -> fetchArea(entityId));
        CompletableFuture<String> densityFuture = populationFuture.thenCombine(areaFuture, WikidataClient::calculateDensity);
        CompletableFuture<List<String>> climatesFuture = executeAsync(() -> fetchClimates(entityId));
        CompletableFuture<List<String>> industriesFuture = executeAsync(() -> fetchIndustries(entityId));
        CompletableFuture<List<String>> culturalFuture = executeAsync(() -> fetchCulturalFactors(entityId));
        CompletableFuture<List<String>> imagesFuture = executeAsync(() -> fetchImageUrls(entityId));

        CompletableFuture.allOf(
                descriptionFuture,
                populationFuture,
                densityFuture,
                climatesFuture,
                industriesFuture,
                culturalFuture,
                imagesFuture
        ).join();

        return new WikidataEnrichment(
                descriptionFuture.join(),
                populationFuture.join(),
                densityFuture.join(),
                climatesFuture.join(),
                industriesFuture.join(),
                culturalFuture.join(),
                imagesFuture.join()
        );
    }

    private String fetchDescription(String entityId) {
        String sparqlQuery = """
                PREFIX wd: <%s>
                PREFIX schema: <%s>
                SELECT ?desc WHERE {
                  wd:%s schema:description ?desc .
                  FILTER(LANG(?desc) = "%s")
                } LIMIT %d
                """.formatted(WD, SCHEMA, entityId, LANG_EN, LIMIT_ONE);

        return sparql.selectFirstString(createRequest(sparqlQuery, "desc"));
    }

    private String fetchPopulationTotal(String entityId) {
        String sparqlQuery = """
                PREFIX wd: <%s>
                PREFIX wdt: <%s>
                SELECT ?population WHERE {
                  wd:%s wdt:P1082 ?population .
                } LIMIT %d
                """.formatted(WD, WDT, entityId, LIMIT_ONE);

        return sparql.selectFirstString(createRequest(sparqlQuery, "population"));
    }

    private String fetchArea(String entityId) {
        String sparqlQuery = """
                PREFIX wd: <%s>
                PREFIX wdt: <%s>
                SELECT ?area WHERE {
                  wd:%s wdt:P2046 ?area .
                } LIMIT %d
                """.formatted(WD, WDT, entityId, LIMIT_ONE);

        return sparql.selectFirstString(createRequest(sparqlQuery, "area"));
    }

    private static String calculateDensity(String populationValue, String areaValue) {
        Double population = parseDouble(populationValue);
        Double area = parseDouble(areaValue);
        if (population == null || area == null || area == 0.0) return null;
        double density = population / area;
        return String.format(Locale.US, "%.2f", density);
    }

    private List<String> fetchClimates(String entityId) {
        String sparqlQuery = """
                PREFIX wd: <%s>
                PREFIX wdt: <%s>
                PREFIX wikibase: <%s>
                PREFIX bd: <%s>

                SELECT DISTINCT ?climateLabel WHERE {
                  wd:%s wdt:P2564 ?climate .
                  SERVICE wikibase:label { bd:serviceParam wikibase:language "%s". }
                } LIMIT %d
                """.formatted(WD, WDT, WIKIBASE, BD, entityId, LANG_EN, LIMIT_LIST);

        return sparql.selectStrings(createRequest(sparqlQuery, "climateLabel"));
    }

    private List<String> fetchIndustries(String entityId) {
        String sparqlQuery = """
                PREFIX wd: <%s>
                PREFIX wdt: <%s>
                PREFIX wikibase: <%s>
                PREFIX bd: <%s>

                SELECT DISTINCT ?industryLabel WHERE {
                  wd:%s wdt:P452 ?industry .
                  SERVICE wikibase:label { bd:serviceParam wikibase:language "%s". }
                } LIMIT %d
                """.formatted(WD, WDT, WIKIBASE, BD, entityId, LANG_EN, LIMIT_LIST);

        return sparql.selectStrings(createRequest(sparqlQuery, "industryLabel"));
    }

    private List<String> fetchCulturalFactors(String entityId) {
        List<String> factors = new ArrayList<>();
        factors.addAll(fetchOfficialLanguages(entityId));
        factors.addAll(fetchDemonyms(entityId));
        return removeDuplicates(factors);
    }

    private List<String> fetchOfficialLanguages(String entityId) {
        String sparqlQuery = """
                PREFIX wd: <%s>
                PREFIX wdt: <%s>
                PREFIX wikibase: <%s>
                PREFIX bd: <%s>

                SELECT DISTINCT ?langLabel WHERE {
                  wd:%s wdt:P37 ?lang .
                  SERVICE wikibase:label { bd:serviceParam wikibase:language "%s". }
                } LIMIT %d
                """.formatted(WD, WDT, WIKIBASE, BD, entityId, LANG_EN, LIMIT_LIST);

        return sparql.selectStrings(createRequest(sparqlQuery, "langLabel"));
    }

    private List<String> fetchDemonyms(String entityId) {
        String sparqlQuery = """
                PREFIX wd: <%s>
                PREFIX wdt: <%s>
                SELECT DISTINCT ?demonym WHERE {
                  wd:%s wdt:P1549 ?demonym .
                  FILTER(LANG(?demonym) = "%s" || LANG(?demonym) = "")
                } LIMIT %d
                """.formatted(WD, WDT, entityId, LANG_EN, LIMIT_LIST);

        return sparql.selectStrings(createRequest(sparqlQuery, "demonym"));
    }

    private List<String> fetchImageUrls(String entityId) {
        String sparqlQuery = """
                PREFIX wd: <%s>
                PREFIX wdt: <%s>
                SELECT ?img WHERE {
                  wd:%s wdt:P18 ?img .
                } LIMIT %d
                """.formatted(WD, WDT, entityId, LIMIT_IMAGES);

        return sparql.selectStrings(createRequest(sparqlQuery, "img"));
    }

    private SparqlHttpClient.SelectRequest createRequest(String sparqlQuery, String varName) {
        return new SparqlHttpClient.SelectRequest(
                endpoint,
                timeoutMs,
                Map.of(SparqlHttpClient.HEADER_USER_AGENT, userAgent),
                sparqlQuery,
                varName,
                "Wikidata"
        );
    }

    private <T> CompletableFuture<T> executeAsync(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, meadExecutor);
    }

    private static Double parseDouble(String value) {
        if (value == null) return null;
        String normalized = value.replace(",", "").trim();
        if (normalized.isBlank()) return null;
        try {
            return Double.parseDouble(normalized);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<String> removeDuplicates(List<String> inputList) {
        Map<String, String> map = new LinkedHashMap<>();
        inputList.forEach(s -> map.putIfAbsent(s.toLowerCase(), s));
        return new ArrayList<>(map.values());
    }
}
