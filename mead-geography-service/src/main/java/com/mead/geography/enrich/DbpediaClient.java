package com.mead.geography.enrich;

import com.mead.geography.config.AsyncConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

@Component
public class DbpediaClient {

    private static final String DBO = "http://dbpedia.org/ontology/";
    private static final String DBP = "http://dbpedia.org/property/";
    private static final String FOAF = "http://xmlns.com/foaf/0.1/";
    private static final String RDFS = "http://www.w3.org/2000/01/rdf-schema#";
    private static final String SCHEMA = "http://schema.org/";

    private static final String LANG_EN = "en";

    private static final int LIMIT_ONE = 1;
    private static final int LIMIT_LABELS = 50;
    private static final int LIMIT_LITERALS = 20;
    private static final int LIMIT_IMAGES = 10;

    @Value("${mead.external.dbpedia.endpoint}")
    private String endpoint;

    @Value("${mead.external.dbpedia.timeout-ms:8000}")
    private long timeoutMs;

    private final SparqlHttpClient sparql;
    private final Executor meadExecutor;

    public DbpediaClient(SparqlHttpClient sparql,
                         @Qualifier(AsyncConfig.MEAD_EXECUTOR) Executor meadExecutor) {
        this.sparql = sparql;
        this.meadExecutor = meadExecutor;
    }

    public record DbpediaEnrichment(
            String description,
            String populationTotal,
            String populationDensity,
            List<String> climates,
            List<String> industries,
            List<String> culturalFactors,
            List<String> images
    ) {}

    @Cacheable("dbpediaEnrichment")
    public DbpediaEnrichment enrichFromResourceUri(String dbpediaResourceUri) {
        CompletableFuture<String> descriptionFuture = executeAsync(() -> fetchEnglishDescription(dbpediaResourceUri));
        CompletableFuture<String> populationTotalFuture = executeAsync(() -> fetchPopulationTotal(dbpediaResourceUri));
        CompletableFuture<String> populationDensityFuture = executeAsync(() -> fetchPopulationDensity(dbpediaResourceUri));
        CompletableFuture<List<String>> climatesFuture = executeAsync(() -> fetchClimates(dbpediaResourceUri));
        CompletableFuture<List<String>> industriesFuture = executeAsync(() -> fetchIndustries(dbpediaResourceUri));
        CompletableFuture<List<String>> culturalFuture = executeAsync(() -> fetchCulturalFactors(dbpediaResourceUri));
        CompletableFuture<List<String>> imagesFuture = executeAsync(() -> fetchImageUrls(dbpediaResourceUri));

        CompletableFuture.allOf(
                descriptionFuture,
                populationTotalFuture,
                populationDensityFuture,
                climatesFuture,
                industriesFuture,
                culturalFuture,
                imagesFuture
        ).join();

        return new DbpediaEnrichment(
                descriptionFuture.join(),
                populationTotalFuture.join(),
                populationDensityFuture.join(),
                climatesFuture.join(),
                industriesFuture.join(),
                culturalFuture.join(),
                imagesFuture.join()
        );
    }

    private String fetchEnglishDescription(String resourceUri) {
        String abstractText = queryEnglishLiteral(resourceUri, DBO + "abstract");
        if (abstractText != null) return abstractText;

        String descriptionText = queryEnglishLiteral(resourceUri, DBO + "description");
        if (descriptionText != null) return descriptionText;

        return queryEnglishLiteral(resourceUri, RDFS + "comment");
    }

    private String fetchPopulationTotal(String resourceUri) {
        String value = queryLiteral(resourceUri, DBO + "populationTotal");
        if (value != null) return value;

        value = queryLiteral(resourceUri, DBP + "populationTotal");
        if (value != null) return value;

        return queryLiteral(resourceUri, DBP + "population");
    }

    private String fetchPopulationDensity(String resourceUri) {
        String value = queryLiteral(resourceUri, DBO + "populationDensity");
        if (value != null) return value;
        return queryLiteral(resourceUri, DBP + "populationDensity");
    }

    private List<String> fetchClimates(String resourceUri) {
        List<String> climates = queryEnglishLabels(resourceUri, DBO + "climate");
        if (!climates.isEmpty()) return removeDuplicates(climates);

        List<String> raw = queryLiterals(resourceUri, DBP + "climate");
        return removeDuplicates(splitList(raw));
    }

    private List<String> fetchIndustries(String resourceUri) {
        List<String> industries = new ArrayList<>();
        industries.addAll(queryEnglishLabels(resourceUri, DBO + "industry"));
        industries.addAll(splitList(queryLiterals(resourceUri, DBP + "industries")));
        industries.addAll(splitList(queryLiterals(resourceUri, DBP + "industry")));
        return removeDuplicates(industries);
    }

    private List<String> fetchCulturalFactors(String resourceUri) {
        List<String> factors = new ArrayList<>();
        factors.addAll(queryEnglishLabels(resourceUri, DBO + "language"));
        factors.addAll(queryEnglishLabels(resourceUri, DBO + "officialLanguage"));
        factors.addAll(splitList(queryLiterals(resourceUri, DBP + "officialLanguages")));
        factors.addAll(splitList(queryLiterals(resourceUri, DBP + "officialLanguage")));
        factors.addAll(queryEnglishLabels(resourceUri, DBO + "demonym"));
        factors.addAll(splitList(queryLiterals(resourceUri, DBP + "demonym")));
        return removeDuplicates(factors);
    }

    private String queryEnglishLiteral(String subjectUri, String predicateUri) {
        String sparqlQuery = """
                SELECT ?text WHERE {
                  <%s> <%s> ?text .
                  FILTER(LANG(?text) = "%s")
                } LIMIT %d
                """.formatted(subjectUri, predicateUri, LANG_EN, LIMIT_ONE);

        return sparql.selectFirstString(createRequest(sparqlQuery, "text"));
    }

    private String queryLiteral(String subjectUri, String predicateUri) {
        String sparqlQuery = """
                SELECT ?value WHERE {
                  <%s> <%s> ?value .
                } LIMIT %d
                """.formatted(subjectUri, predicateUri, LIMIT_ONE);

        return sparql.selectFirstString(createRequest(sparqlQuery, "value"));
    }

    private List<String> queryLiterals(String subjectUri, String predicateUri) {
        String sparqlQuery = """
                SELECT DISTINCT ?value WHERE {
                  <%s> <%s> ?value .
                  FILTER(LANG(?value) = "%s" || LANG(?value) = "")
                } LIMIT %d
                """.formatted(subjectUri, predicateUri, LANG_EN, LIMIT_LITERALS);

        return sparql.selectStrings(createRequest(sparqlQuery, "value"));
    }

    private List<String> queryEnglishLabels(String subjectUri, String predicateUri) {
        String sparqlQuery = """
                PREFIX rdfs: <%s>
                SELECT DISTINCT ?label WHERE {
                  <%s> <%s> ?item .
                  ?item rdfs:label ?label .
                  FILTER(LANG(?label) = "%s")
                } LIMIT %d
                """.formatted(RDFS, subjectUri, predicateUri, LANG_EN, LIMIT_LABELS);

        return sparql.selectStrings(createRequest(sparqlQuery, "label"));
    }

    private List<String> fetchImageUrls(String resourceUri) {
        String sparqlQuery = """
                PREFIX dbo: <%s>
                PREFIX dbp: <%s>
                PREFIX foaf: <%s>
                PREFIX schema: <%s>
                SELECT DISTINCT ?img WHERE {
                  { <%s> dbo:thumbnail ?img . }
                  UNION { <%s> foaf:depiction ?img . }
                  UNION { <%s> schema:image ?img . }
                  UNION { <%s> dbp:image ?img . }
                } LIMIT %d
                """.formatted(DBO, DBP, FOAF, SCHEMA, resourceUri, resourceUri, resourceUri, resourceUri, LIMIT_IMAGES);

        return sparql.selectStrings(createRequest(sparqlQuery, "img"));
    }

    private SparqlHttpClient.SelectRequest createRequest(String sparqlQuery, String varName) {
        return new SparqlHttpClient.SelectRequest(
                endpoint,
                timeoutMs,
                Map.of(),
                sparqlQuery,
                varName,
                "DBpedia"
        );
    }

    private <T> CompletableFuture<T> executeAsync(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, meadExecutor);
    }

    private static List<String> splitList(List<String> rawList) {
        return rawList.stream()
                .flatMap(s -> Arrays.stream(s.split("[,;]")))
                .map(String::trim)
                .filter(x -> !x.isBlank())
                .toList();
    }

    private static List<String> removeDuplicates(List<String> inputList) {
        Map<String, String> map = new LinkedHashMap<>();
        inputList.forEach(s -> map.putIfAbsent(s.toLowerCase(), s));
        return new ArrayList<>(map.values());
    }
}
