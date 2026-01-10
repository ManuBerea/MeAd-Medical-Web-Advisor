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
    private static final int LIMIT_IMAGES = 50;

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
        CompletableFuture<String> descriptionFuture = future(() -> fetchEnglishDescription(dbpediaResourceUri));
        CompletableFuture<List<String>> symptomsFuture = future(() -> fetchSymptoms(dbpediaResourceUri));
        CompletableFuture<List<String>> riskFactorsFuture = future(() -> fetchRiskFactors(dbpediaResourceUri));
        CompletableFuture<List<String>> imagesFuture = future(() -> fetchImageUrls(dbpediaResourceUri));

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
        List<String> ontologySymptoms = sparql.selectStrings(request("""
                PREFIX dbo: <%s>
                PREFIX rdfs: <%s>
                SELECT DISTINCT ?label WHERE {
                  <%s> dbo:symptom ?symptomResource .
                  ?symptomResource rdfs:label ?label .
                  FILTER(LANG(?label) = "%s")
                } LIMIT %d
                """.formatted(DBO, RDFS, resourceUri, LANG_EN, LIMIT_LABELS), "label"));

        if (!ontologySymptoms.isEmpty()) return removeDuplicates(ontologySymptoms);

        List<String> raw = sparql.selectStrings(request("""
                PREFIX dbp: <%s>
                SELECT DISTINCT ?symptomLiteral WHERE {
                  <%s> dbp:symptoms ?symptomLiteral .
                  FILTER(LANG(?symptomLiteral) = "%s" || LANG(?symptomLiteral) = "")
                } LIMIT %d
                """.formatted(DBP, resourceUri, LANG_EN, LIMIT_LITERALS), "symptomLiteral"));

        return removeDuplicates(splitCommaList(raw));
    }

    private List<String> fetchRiskFactors(String resourceUri) {
        List<String> causes = new ArrayList<>();

        causes.addAll(sparql.selectStrings(request("""
                PREFIX dbo: <%s>
                PREFIX rdfs: <%s>
                SELECT DISTINCT ?label WHERE {
                  <%s> dbo:medicalCause ?causeResource .
                  ?causeResource rdfs:label ?label .
                  FILTER(LANG(?label) = "%s")
                } LIMIT %d
                """.formatted(DBO, RDFS, resourceUri, LANG_EN, LIMIT_LABELS), "label")));

        causes.addAll(splitCommaList(sparql.selectStrings(request("""
                PREFIX dbp: <%s>
                SELECT DISTINCT ?causeLiteral WHERE {
                  <%s> dbp:causes ?causeLiteral .
                  FILTER(LANG(?causeLiteral) = "%s" || LANG(?causeLiteral) = "")
                } LIMIT %d
                """.formatted(DBP, resourceUri, LANG_EN, LIMIT_LITERALS), "causeLiteral"))));

        causes = removeDuplicates(causes);
        if (!causes.isEmpty()) return causes;

        List<String> risks = new ArrayList<>();

        risks.addAll(splitCommaList(sparql.selectStrings(request("""
                PREFIX dbp: <%s>
                SELECT DISTINCT ?complicationLiteral WHERE {
                  <%s> dbp:complications ?complicationLiteral .
                  FILTER(LANG(?complicationLiteral) = "%s" || LANG(?complicationLiteral) = "")
                } LIMIT %d
                """.formatted(DBP, resourceUri, LANG_EN, LIMIT_LITERALS), "complicationLiteral"))));

        risks.addAll(splitCommaList(sparql.selectStrings(request("""
                PREFIX dbo: <%s>
                SELECT DISTINCT ?complicationLiteral WHERE {
                  <%s> dbo:complications ?complicationLiteral .
                  FILTER(LANG(?complicationLiteral) = "%s" || LANG(?complicationLiteral) = "")
                } LIMIT %d
                """.formatted(DBO, resourceUri, LANG_EN, LIMIT_LITERALS), "complicationLiteral"))));

        return removeDuplicates(risks);
    }

    private String queryEnglishLiteral(String subjectUri, String predicateUri) {
        String sparqlQuery = """
                SELECT ?text WHERE {
                  <%s> <%s> ?text .
                  FILTER(LANG(?text) = "%s")
                } LIMIT %d
                """.formatted(subjectUri, predicateUri, LANG_EN, LIMIT_ONE);

        return sparql.selectFirstString(request(sparqlQuery, "text"));
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

        List<String> raw = sparql.selectStrings(request(sparqlQuery, "img"));
        List<String> normalized = raw.stream()
                .map(DbpediaClient::stripQueryParams)
                .filter(s -> s != null && !s.isBlank())
                .toList();
        return removeDuplicates(normalized);
    }

    private SparqlHttpClient.SelectRequest request(String sparqlQuery, String varName) {
        return new SparqlHttpClient.SelectRequest(
                endpoint,
                timeoutMs,
                Map.of(),
                sparqlQuery,
                varName,
                "DBpedia"
        );
    }

    private <T> CompletableFuture<T> future(Supplier<T> supplier) {
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

    private static String stripQueryParams(String url) {
        if (url == null) return null;
        int q = url.indexOf('?');
        return q >= 0 ? url.substring(0, q) : url;
    }
}
