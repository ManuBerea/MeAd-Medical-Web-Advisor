package com.mead.conditions.enrich;

import org.apache.jena.query.*;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.sparql.exec.http.QueryExecutionHTTP;
import org.apache.jena.sparql.exec.http.QueryExecutionHTTPBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@Component
public class WikidataClient {

    private static final Logger log = LoggerFactory.getLogger(WikidataClient.class);
    private static final String SPARQL_RESULTS_JSON = "application/sparql-results+json";

    @Value("${mead.external.wikidata.endpoint}")
    private String endpoint;

    @Value("${mead.external.wikidata.user-agent}")
    private String userAgent;

    @Value("${mead.external.wikidata.timeout-ms:8000}")
    private long timeoutMs;

    public record WikidataEnrichment(
            String description,
            List<String> symptoms,
            List<String> riskFactors,
            String image
    ) {}

    @Cacheable("wikidataEnrichment")
    public WikidataEnrichment enrichFromEntityUri(String wikidataEntityUri) {
        String entityId = qidFromEntityUri(wikidataEntityUri);

        CompletableFuture<String> descriptionFuture = CompletableFuture.supplyAsync(() -> selectFirstString("""
                PREFIX wd: <http://www.wikidata.org/entity/>
                PREFIX schema: <http://schema.org/>
                SELECT ?desc WHERE {
                  wd:%s schema:description ?desc .
                  FILTER(LANG(?desc) = "en")
                } LIMIT 1
                """.formatted(entityId), "desc"));

        CompletableFuture<List<String>> symptomsFuture = CompletableFuture.supplyAsync(() -> selectStrings("""
                PREFIX wd: <http://www.wikidata.org/entity/>
                PREFIX wdt: <http://www.wikidata.org/prop/direct/>
                PREFIX wikibase: <http://wikiba.se/ontology#>
                PREFIX bd: <http://www.bigdata.com/rdf#>
                
                SELECT DISTINCT ?symptomLabel WHERE {
                  wd:%s wdt:P780 ?symptom .
                  SERVICE wikibase:label { bd:serviceParam wikibase:language "en". }
                } LIMIT 30
                """.formatted(entityId), "symptomLabel"));

        CompletableFuture<List<String>> riskFactorsFuture = CompletableFuture.supplyAsync(() -> selectStrings("""
                PREFIX wd: <http://www.wikidata.org/entity/>
                PREFIX wdt: <http://www.wikidata.org/prop/direct/>
                PREFIX wikibase: <http://wikiba.se/ontology#>
                PREFIX bd: <http://www.bigdata.com/rdf#>
                
                SELECT DISTINCT ?rfLabel WHERE {
                  wd:%s wdt:P5642 ?rf .
                  SERVICE wikibase:label { bd:serviceParam wikibase:language "en". }
                } LIMIT 30
                """.formatted(entityId), "rfLabel"));

        CompletableFuture<String> imageFuture = CompletableFuture.supplyAsync(() -> selectFirstAnyNodeAsString("""
                PREFIX wd: <http://www.wikidata.org/entity/>
                PREFIX wdt: <http://www.wikidata.org/prop/direct/>
                
                SELECT ?img WHERE {
                  wd:%s wdt:P18 ?img .
                } LIMIT 1
                """.formatted(entityId)));

        CompletableFuture.allOf(descriptionFuture, symptomsFuture, riskFactorsFuture, imageFuture).join();

        return new WikidataEnrichment(
                descriptionFuture.join(),
                symptomsFuture.join(),
                riskFactorsFuture.join(),
                normalizeToCommonsFilePath(imageFuture.join())
        );
    }

    public static String qidFromEntityUri(String wikidataEntityUri) {
        return wikidataEntityUri.substring(wikidataEntityUri.lastIndexOf('/') + 1);
    }

    /**
     * Normalizes a Wikidata P18 value to a single, working direct URL.
     * Handles:
     * - literal filename: "Asthma.jpg"
     * - URI: <a href="http://commons.wikimedia.org/wiki/Special:FilePath/Obesity-waist%20circumference.svg">...</a>
     */
    private static String normalizeToCommonsFilePath(String input) {
        if (input == null || input.isBlank()) return null;

        // If it already contains Special: FilePath/, keep only the tail and rebuild once (avoid double prefix)
        int filePathIndex = input.lastIndexOf("Special:FilePath/");
        if (filePathIndex >= 0) {
            String tail = input.substring(filePathIndex + "Special:FilePath/".length());
            return "https://commons.wikimedia.org/wiki/Special:FilePath/" + tail;
        }

        // If it's already a common URL, just force https
        if (input.startsWith("http://commons.wikimedia.org/")) {
            return "https://commons.wikimedia.org/" + input.substring("http://commons.wikimedia.org/".length());
        }
        if (input.startsWith("https://commons.wikimedia.org/")) {
            return input;
        }

        // Otherwise treat it like a filename
        String filename = input;
        if (filename.startsWith("File:")) filename = filename.substring("File:".length());
        filename = filename.replace(" ", "_");
        return "https://commons.wikimedia.org/wiki/Special:FilePath/" + urlEncode(filename);
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private List<String> selectStrings(String sparql, String varName) {
        List<String> results = new ArrayList<>();
        try (QueryExecutionHTTP queryExecution = (QueryExecutionHTTP) QueryExecutionHTTPBuilder
                .service(endpoint)
                .query(sparql)
                .httpHeader("User-Agent", userAgent)
                .acceptHeader(SPARQL_RESULTS_JSON)
                .timeout(timeoutMs)
                .build()) {

            ResultSet resultSet = queryExecution.execSelect();
            while (resultSet.hasNext()) {
                QuerySolution row = resultSet.next();
                RDFNode node = row.get(varName);
                if (node == null) continue;
                if (node.isLiteral()) results.add(node.asLiteral().getString());
                else if (node.isResource()) results.add(node.asResource().getURI());
                else results.add(node.toString());
            }
        } catch (Exception e) {
            log.warn("Wikidata query failed for var {}: {}", varName, e.getMessage());
        }
        return results;
    }

    private String selectFirstString(String sparql, String varName) {
        List<String> values = selectStrings(sparql, varName);
        return values.isEmpty() ? null : values.get(0);
    }

    private String selectFirstAnyNodeAsString(String sparql) {
        return selectFirstString(sparql, "img");
    }
}
