package com.mead.conditions.enrich;

import org.apache.jena.query.*;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.sparql.exec.http.QueryExecutionHTTP;
import org.apache.jena.sparql.exec.http.QueryExecutionHTTPBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
public class WikidataClient {

    private static final Logger log = LoggerFactory.getLogger(WikidataClient.class);

    @Value("${mead.external.wikidata.endpoint}")
    private String endpoint;

    @Value("${mead.external.wikidata.user-agent}")
    private String userAgent;

    @Value("${mead.external.wikidata.timeout-ms:8000}")
    private long timeoutMs;

    public record WikidataEnrichment(
            String description,       // schema:description (en)
            List<String> symptoms,    // P780 labels
            List<String> riskFactors, // P5642 labels
            String image              // ONE usable URL
    ) {}

    public WikidataEnrichment enrichFromEntityUri(String wikidataEntityUri) {
        String qid = qidFromEntityUri(wikidataEntityUri);

        String description = selectFirstString("""
            PREFIX wd: <http://www.wikidata.org/entity/>
            PREFIX schema: <http://schema.org/>
            SELECT ?desc WHERE {
              wd:%s schema:description ?desc .
              FILTER(LANG(?desc) = "en")
            } LIMIT 1
            """.formatted(qid), "desc");

        // NOTE: bd: prefix is required for bd:serviceParam in label service. :contentReference[oaicite:2]{index=2}
        List<String> symptoms = selectStrings("""
            PREFIX wd: <http://www.wikidata.org/entity/>
            PREFIX wdt: <http://www.wikidata.org/prop/direct/>
            PREFIX wikibase: <http://wikiba.se/ontology#>
            PREFIX bd: <http://www.bigdata.com/rdf#>

            SELECT DISTINCT ?symptomLabel WHERE {
              wd:%s wdt:P780 ?symptom .
              SERVICE wikibase:label { bd:serviceParam wikibase:language "en". }
            } LIMIT 30
            """.formatted(qid), "symptomLabel");

        List<String> riskFactors = selectStrings("""
            PREFIX wd: <http://www.wikidata.org/entity/>
            PREFIX wdt: <http://www.wikidata.org/prop/direct/>
            PREFIX wikibase: <http://wikiba.se/ontology#>
            PREFIX bd: <http://www.bigdata.com/rdf#>

            SELECT DISTINCT ?rfLabel WHERE {
              wd:%s wdt:P5642 ?rf .
              SERVICE wikibase:label { bd:serviceParam wikibase:language "en". }
            } LIMIT 30
            """.formatted(qid), "rfLabel");

        // Image (P18) — take the first one
        String rawImg = selectFirstAnyNodeAsString("""
            PREFIX wd: <http://www.wikidata.org/entity/>
            PREFIX wdt: <http://www.wikidata.org/prop/direct/>

            SELECT ?img WHERE {
              wd:%s wdt:P18 ?img .
            } LIMIT 1
            """.formatted(qid), "img");

        String imageUrl = normalizeToCommonsFilePath(rawImg);

        return new WikidataEnrichment(description, symptoms, riskFactors, imageUrl);
    }

    public static String qidFromEntityUri(String wikidataEntityUri) {
        return wikidataEntityUri.substring(wikidataEntityUri.lastIndexOf('/') + 1);
    }

    /**
     * Normalizes a Wikidata P18 value to a single, working direct URL.
     * Handles:
     * - literal filename: "Asthma.jpg"
     * - URI: http://commons.wikimedia.org/wiki/Special:FilePath/Obesity-waist%20circumference.svg
     */
    private static String normalizeToCommonsFilePath(String raw) {
        if (raw == null || raw.isBlank()) return null;

        String s = raw;

        // If it already contains Special:FilePath/, keep only the tail and rebuild once (avoid double prefix)
        int idx = s.lastIndexOf("Special:FilePath/");
        if (idx >= 0) {
            String tail = s.substring(idx + "Special:FilePath/".length());
            return "https://commons.wikimedia.org/wiki/Special:FilePath/" + tail;
        }

        // If it's already a commons URL, just force https
        if (s.startsWith("http://commons.wikimedia.org/")) {
            return "https://commons.wikimedia.org/" + s.substring("http://commons.wikimedia.org/".length());
        }
        if (s.startsWith("https://commons.wikimedia.org/")) {
            return s;
        }

        // Otherwise treat it like a filename
        String filename = s;
        if (filename.startsWith("File:")) filename = filename.substring("File:".length());
        filename = filename.replace(" ", "_");
        return "https://commons.wikimedia.org/wiki/Special:FilePath/" + urlEncode(filename);
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private List<String> selectStrings(String sparql, String var) {
        List<String> out = new ArrayList<>();
        try (QueryExecutionHTTP exec = (QueryExecutionHTTP) QueryExecutionHTTPBuilder
                .service(endpoint)
                .query(sparql)
                .httpHeader("User-Agent", userAgent)
                .acceptHeader("application/sparql-results+json")
                .timeout(timeoutMs)
                .build()) {

            ResultSet rs = exec.execSelect();
            while (rs.hasNext()) {
                QuerySolution row = rs.next();
                RDFNode node = row.get(var);
                if (node == null) continue;
                if (node.isLiteral()) out.add(node.asLiteral().getString());
                else if (node.isResource()) out.add(node.asResource().getURI());
                else out.add(node.toString());
            }
        } catch (Exception e) {
            log.warn("Wikidata query failed for var {}: {}", var, e.getMessage());
        }
        return out;
    }

    private String selectFirstString(String sparql, String var) {
        List<String> vals = selectStrings(sparql, var);
        return vals.isEmpty() ? null : vals.get(0);
    }

    private String selectFirstAnyNodeAsString(String sparql, String var) {
        return selectFirstString(sparql, var);
    }
}
