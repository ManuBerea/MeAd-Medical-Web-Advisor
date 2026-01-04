package com.mead.conditions.enrich;

import org.apache.jena.query.*;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.sparql.exec.http.QueryExecutionHTTP;
import org.apache.jena.sparql.exec.http.QueryExecutionHTTPBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;

@Component
public class DbpediaClient {

    @Value("${mead.external.dbpedia.endpoint}")
    private String endpoint;

    @Value("${mead.external.dbpedia.timeout-ms:8000}")
    private long timeoutMs;

    /** abstract -> description -> comment */
    public String englishDescription(String dbpediaResourceUri) {
        String abs = queryEnglishLiteral(dbpediaResourceUri, "http://dbpedia.org/ontology/abstract");
        if (abs != null) return abs;

        String desc = queryEnglishLiteral(dbpediaResourceUri, "http://dbpedia.org/ontology/description");
        if (desc != null) return desc;

        return queryEnglishLiteral(dbpediaResourceUri, "http://www.w3.org/2000/01/rdf-schema#comment");
    }

    /** Best-effort: returns a usable thumbnail URL if present (often a commons Special:FilePath URL with ?width=...) */
    public String thumbnailUrl(String dbpediaResourceUri) {
        String q = """
            PREFIX dbo: <http://dbpedia.org/ontology/>
            SELECT ?t WHERE {
              <%s> dbo:thumbnail ?t .
            } LIMIT 1
            """.formatted(dbpediaResourceUri);

        RDFNode n = selectFirstNode(q, "t");
        if (n == null) return null;
        String url = n.isResource() ? n.asResource().getURI() : n.toString();

        // Remove width param if present (still usable either way)
        int qIdx = url.indexOf('?');
        return (qIdx >= 0) ? url.substring(0, qIdx) : url;
    }

    /** Symptoms: try dbo:symptom (rare) and dbp:symptoms (common for medical infoboxes) */
    public List<String> symptoms(String dbpediaResourceUri) {
        // 1) dbo:symptom (resource values)
        List<String> dbo = selectEnglishLabels("""
            PREFIX dbo: <http://dbpedia.org/ontology/>
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            SELECT DISTINCT ?label WHERE {
              <%s> dbo:symptom ?x .
              ?x rdfs:label ?label .
              FILTER(LANG(?label)="en")
            } LIMIT 50
            """.formatted(dbpediaResourceUri), "label");

        if (!dbo.isEmpty()) return dbo;

        // 2) dbp:symptoms (usually literal string like "Increased fat")
        List<String> raw = selectLiteralStrings("""
            PREFIX dbp: <http://dbpedia.org/property/>
            SELECT DISTINCT ?s WHERE {
              <%s> dbp:symptoms ?s .
              FILTER(LANG(?s)="en" || LANG(?s) = "")
            } LIMIT 10
            """.formatted(dbpediaResourceUri), "s");

        return splitList(raw);
    }

    /**
     * Risk factors fallback:
     * - dbp:causes (literal list, often comma-separated)
     * - dbo:medicalCause (resources)
     * - dbo:complications/dbp:complications (these are "risks" rather than causes; we include them if causes are missing)
     */
    public List<String> riskFactorsOrRisks(String dbpediaResourceUri) {
        List<String> causes = new ArrayList<>();

        // dbo:medicalCause (resources -> labels)
        causes.addAll(selectEnglishLabels("""
            PREFIX dbo: <http://dbpedia.org/ontology/>
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            SELECT DISTINCT ?label WHERE {
              <%s> dbo:medicalCause ?x .
              ?x rdfs:label ?label .
              FILTER(LANG(?label)="en")
            } LIMIT 50
            """.formatted(dbpediaResourceUri), "label"));

        // dbp:causes (literal string list)
        causes.addAll(splitList(selectLiteralStrings("""
            PREFIX dbp: <http://dbpedia.org/property/>
            SELECT DISTINCT ?c WHERE {
              <%s> dbp:causes ?c .
              FILTER(LANG(?c)="en" || LANG(?c) = "")
            } LIMIT 10
            """.formatted(dbpediaResourceUri), "c")));

        causes = dedupe(causes);
        if (!causes.isEmpty()) return causes;

        // If no causes, fall back to complications ("risks")
        List<String> risks = splitList(selectLiteralStrings("""
            PREFIX dbp: <http://dbpedia.org/property/>
            SELECT DISTINCT ?c WHERE {
              <%s> dbp:complications ?c .
              FILTER(LANG(?c)="en" || LANG(?c) = "")
            } LIMIT 10
            """.formatted(dbpediaResourceUri), "c"));

        // dbo:complications sometimes exists too
        risks.addAll(splitList(selectLiteralStrings("""
            PREFIX dbo: <http://dbpedia.org/ontology/>
            SELECT DISTINCT ?c WHERE {
              <%s> dbo:complications ?c .
              FILTER(LANG(?c)="en" || LANG(?c) = "")
            } LIMIT 10
            """.formatted(dbpediaResourceUri), "c")));

        return dedupe(risks);
    }

    // ---------- helpers ----------

    private String queryEnglishLiteral(String subjectUri, String predicateUri) {
        String q = """
            SELECT ?txt WHERE {
              <%s> <%s> ?txt .
              FILTER(LANG(?txt) = "en")
            } LIMIT 1
            """.formatted(subjectUri, predicateUri);

        try (QueryExecutionHTTP exec = (QueryExecutionHTTP) QueryExecutionHTTPBuilder
                .service(endpoint)
                .query(q)
                .acceptHeader("application/sparql-results+json")
                .timeout(timeoutMs)
                .build()) {

            ResultSet rs = exec.execSelect();
            if (!rs.hasNext()) return null;
            return rs.next().getLiteral("txt").getString();
        } catch (Exception e) {
            return null;
        }
    }

    private RDFNode selectFirstNode(String sparql, String var) {
        try (QueryExecutionHTTP exec = (QueryExecutionHTTP) QueryExecutionHTTPBuilder
                .service(endpoint)
                .query(sparql)
                .acceptHeader("application/sparql-results+json")
                .timeout(timeoutMs)
                .build()) {

            ResultSet rs = exec.execSelect();
            if (!rs.hasNext()) return null;
            return rs.next().get(var);
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> selectLiteralStrings(String sparql, String var) {
        List<String> out = new ArrayList<>();
        try (QueryExecutionHTTP exec = (QueryExecutionHTTP) QueryExecutionHTTPBuilder
                .service(endpoint)
                .query(sparql)
                .acceptHeader("application/sparql-results+json")
                .timeout(timeoutMs)
                .build()) {

            ResultSet rs = exec.execSelect();
            while (rs.hasNext()) {
                QuerySolution row = rs.next();
                RDFNode n = row.get(var);
                if (n != null && n.isLiteral()) out.add(n.asLiteral().getString());
            }
        } catch (Exception e) {
            // ignore
        }
        return out;
    }

    private List<String> selectEnglishLabels(String sparql, String var) {
        return selectLiteralStrings(sparql, var);
    }

    private static List<String> splitList(List<String> raw) {
        // DBpedia infobox fields often store comma-separated lists in one literal.
        return raw.stream()
                .flatMap(s -> Arrays.stream(s.split(",")))
                .map(String::trim)
                .filter(x -> !x.isBlank())
                .collect(Collectors.toList());
    }

    private static List<String> dedupe(List<String> in) {
        Map<String, String> caseMap = new LinkedHashMap<>();
        in.forEach(s -> caseMap.putIfAbsent(s.toLowerCase(), s));
        return new ArrayList<>(caseMap.values());
    }
}
