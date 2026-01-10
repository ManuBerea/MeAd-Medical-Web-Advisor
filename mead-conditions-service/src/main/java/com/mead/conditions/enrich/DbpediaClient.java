package com.mead.conditions.enrich;

import com.mead.conditions.config.AsyncConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
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

    private static final int LIMIT_LITERALS = 10;
    private static final int LIMIT_ONE = 1;
    private static final int LIMIT_LABELS = 50;
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
            List<String> symptoms,
            List<String> riskFactors,
            List<String> images
    ) {}

    @Cacheable("dbpediaEnrichment")
    public DbpediaEnrichment enrichFromResourceUri(String dbpediaResourceUri) {
        CompletableFuture<String> descriptionFuture = executeAsync(() -> fetchEnglishDescription(dbpediaResourceUri));
        CompletableFuture<List<String>> symptomsFuture = executeAsync(() -> fetchSymptoms(dbpediaResourceUri));
        CompletableFuture<List<String>> riskFactorsFuture = executeAsync(() -> fetchRiskFactors(dbpediaResourceUri));
        CompletableFuture<List<String>> imagesFuture = executeAsync(() -> fetchImageUrls(dbpediaResourceUri));

        CompletableFuture.allOf(descriptionFuture, symptomsFuture, riskFactorsFuture, imagesFuture).join();

        return new DbpediaEnrichment(
                descriptionFuture.join(),
                symptomsFuture.join(),
                riskFactorsFuture.join(),
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

    private List<String> fetchSymptoms(String resourceUri) {
        List<String> ontologySymptoms = sparql.selectStrings(createRequest("""
                PREFIX dbo: <%s>
                PREFIX rdfs: <%s>
                SELECT DISTINCT ?label WHERE {
                  <%s> dbo:symptom ?symptomResource .
                  ?symptomResource rdfs:label ?label .
                  FILTER(LANG(?label) = "%s")
                } LIMIT %d
                """.formatted(DBO, RDFS, resourceUri, LANG_EN, LIMIT_LABELS), "label"));

        if (!ontologySymptoms.isEmpty()) return removeDuplicates(ontologySymptoms);

        List<String> rawLiterals = sparql.selectStrings(createRequest("""
                PREFIX dbp: <%s>
                SELECT DISTINCT ?symptomLiteral WHERE {
                  <%s> dbp:symptoms ?symptomLiteral .
                  FILTER(LANG(?symptomLiteral) = "%s" || LANG(?symptomLiteral) = "")
                } LIMIT %d
                """.formatted(DBP, resourceUri, LANG_EN, LIMIT_LITERALS), "symptomLiteral"));

        return removeDuplicates(splitCommaList(rawLiterals));
    }

    private List<String> fetchRiskFactors(String resourceUri) {
        List<String> riskFactors = new ArrayList<>();

        riskFactors.addAll(sparql.selectStrings(createRequest("""
                PREFIX dbo: <%s>
                PREFIX rdfs: <%s>
                SELECT DISTINCT ?label WHERE {
                  <%s> dbo:medicalCause ?causeResource .
                  ?causeResource rdfs:label ?label .
                  FILTER(LANG(?label) = "%s")
                } LIMIT %d
                """.formatted(DBO, RDFS, resourceUri, LANG_EN, LIMIT_LABELS), "label")));

        riskFactors.addAll(splitCommaList(sparql.selectStrings(createRequest("""
                PREFIX dbp: <%s>
                SELECT DISTINCT ?causeLiteral WHERE {
                  <%s> dbp:causes ?causeLiteral .
                  FILTER(LANG(?causeLiteral) = "%s" || LANG(?causeLiteral) = "")
                } LIMIT %d
                """.formatted(DBP, resourceUri, LANG_EN, LIMIT_LITERALS), "causeLiteral"))));

        riskFactors = removeDuplicates(riskFactors);
        if (!riskFactors.isEmpty()) return riskFactors;

        List<String> complications = new ArrayList<>();

        complications.addAll(splitCommaList(sparql.selectStrings(createRequest("""
                PREFIX dbp: <%s>
                SELECT DISTINCT ?complicationLiteral WHERE {
                  <%s> dbp:complications ?complicationLiteral .
                  FILTER(LANG(?complicationLiteral) = "%s" || LANG(?complicationLiteral) = "")
                } LIMIT %d
                """.formatted(DBP, resourceUri, LANG_EN, LIMIT_LITERALS), "complicationLiteral"))));

        complications.addAll(splitCommaList(sparql.selectStrings(createRequest("""
                PREFIX dbo: <%s>
                SELECT DISTINCT ?complicationLiteral WHERE {
                  <%s> dbo:complications ?complicationLiteral .
                  FILTER(LANG(?complicationLiteral) = "%s" || LANG(?complicationLiteral) = "")
                } LIMIT %d
                """.formatted(DBO, resourceUri, LANG_EN, LIMIT_LITERALS), "complicationLiteral"))));

        return removeDuplicates(complications);
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

    private static List<String> splitCommaList(List<String> rawList) {
        return rawList.stream()
                .flatMap(s -> Arrays.stream(s.split(",")))
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
