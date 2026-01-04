package com.mead.conditions.enrich;

import org.apache.jena.query.*;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.sparql.exec.http.QueryExecutionHTTP;
import org.apache.jena.sparql.exec.http.QueryExecutionHTTPBuilder;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;

@Component
public class DbpediaClient {

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(DbpediaClient.class);

    private static final String DBO_ABSTRACT = "http://dbpedia.org/ontology/abstract";
    private static final String DBO_DESCRIPTION = "http://dbpedia.org/ontology/description";
    private static final String RDFS_COMMENT = "http://www.w3.org/2000/01/rdf-schema#comment";
    private static final String SPARQL_RESULTS_JSON = "application/sparql-results+json";

    @Value("${mead.external.dbpedia.endpoint}")
    private String endpoint;

    @Value("${mead.external.dbpedia.timeout-ms:8000}")
    private long timeoutMs;

    @Cacheable("dbpediaDescription")
    public String englishDescription(String dbpediaResourceUri) {
        String abstractLiteral = queryEnglishLiteral(dbpediaResourceUri, DBO_ABSTRACT);
        if (abstractLiteral != null) return abstractLiteral;

        String descriptionLiteral = queryEnglishLiteral(dbpediaResourceUri, DBO_DESCRIPTION);
        if (descriptionLiteral != null) return descriptionLiteral;

        return queryEnglishLiteral(dbpediaResourceUri, RDFS_COMMENT);
    }

    @Cacheable("dbpediaThumbnail")
    public String thumbnailUrl(String dbpediaResourceUri) {
        String sparqlQuery = """
            PREFIX dbo: <http://dbpedia.org/ontology/>
            SELECT ?thumbnail WHERE {
              <%s> dbo:thumbnail ?thumbnail .
            } LIMIT 1
            """.formatted(dbpediaResourceUri);

        RDFNode thumbnailNode = selectFirstNode(sparqlQuery);
        if (thumbnailNode == null) return null;
        String url = thumbnailNode.isResource() ? thumbnailNode.asResource().getURI() : thumbnailNode.toString();

        int queryIndex = url.indexOf('?');
        return (queryIndex >= 0) ? url.substring(0, queryIndex) : url;
    }

    @Cacheable("dbpediaSymptoms")
    public List<String> symptoms(String dbpediaResourceUri) {
        List<String> ontologySymptoms = selectEnglishLabels("""
            PREFIX dbo: <http://dbpedia.org/ontology/>
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            SELECT DISTINCT ?label WHERE {
              <%s> dbo:symptom ?symptomResource .
              ?symptomResource rdfs:label ?label .
              FILTER(LANG(?label)="en")
            } LIMIT 50
            """.formatted(dbpediaResourceUri));

        if (!ontologySymptoms.isEmpty()) return ontologySymptoms;

        List<String> propertySymptoms = selectLiteralStrings("""
            PREFIX dbp: <http://dbpedia.org/property/>
            SELECT DISTINCT ?symptomLiteral WHERE {
              <%s> dbp:symptoms ?symptomLiteral .
              FILTER(LANG(?symptomLiteral)="en" || LANG(?symptomLiteral) = "")
            } LIMIT 10
            """.formatted(dbpediaResourceUri), "symptomLiteral");

        return splitList(propertySymptoms);
    }

    /**
     * Risk factors fallback:
     * - dbp:causes (literal list, often comma-separated)
     * - dbo:medicalCause (resources)
     * - dbo:complications/dbp:complications (these are "risks" rather than causes; we include them if causes are missing)
     */
    @Cacheable("dbpediaRiskFactors")
    public List<String> riskFactorsOrRisks(String dbpediaResourceUri) {
        List<String> causes = new ArrayList<>();

        causes.addAll(selectEnglishLabels("""
            PREFIX dbo: <http://dbpedia.org/ontology/>
            PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
            SELECT DISTINCT ?label WHERE {
              <%s> dbo:medicalCause ?causeResource .
              ?causeResource rdfs:label ?label .
              FILTER(LANG(?label)="en")
            } LIMIT 50
            """.formatted(dbpediaResourceUri)));

        causes.addAll(splitList(selectLiteralStrings("""
            PREFIX dbp: <http://dbpedia.org/property/>
            SELECT DISTINCT ?causeLiteral WHERE {
              <%s> dbp:causes ?causeLiteral .
              FILTER(LANG(?causeLiteral)="en" || LANG(?causeLiteral) = "")
            } LIMIT 10
            """.formatted(dbpediaResourceUri), "causeLiteral")));

        causes = removeDuplicates(causes);
        if (!causes.isEmpty()) return causes;

        List<String> risks = splitList(selectLiteralStrings("""
            PREFIX dbp: <http://dbpedia.org/property/>
            SELECT DISTINCT ?complicationLiteral WHERE {
              <%s> dbp:complications ?complicationLiteral .
              FILTER(LANG(?complicationLiteral)="en" || LANG(?complicationLiteral) = "")
            } LIMIT 10
            """.formatted(dbpediaResourceUri), "complicationLiteral"));

        risks.addAll(splitList(selectLiteralStrings("""
            PREFIX dbo: <http://dbpedia.org/ontology/>
            SELECT DISTINCT ?complicationLiteral WHERE {
              <%s> dbo:complications ?complicationLiteral .
              FILTER(LANG(?complicationLiteral)="en" || LANG(?complicationLiteral) = "")
            } LIMIT 10
            """.formatted(dbpediaResourceUri), "complicationLiteral")));

        return removeDuplicates(risks);
    }

    private String queryEnglishLiteral(String subjectUri, String predicateUri) {
        String sparqlQuery = """
            SELECT ?text WHERE {
              <%s> <%s> ?text .
              FILTER(LANG(?text) = "en")
            } LIMIT 1
            """.formatted(subjectUri, predicateUri);

        try (QueryExecutionHTTP queryExecution = (QueryExecutionHTTP) QueryExecutionHTTPBuilder
                .service(endpoint)
                .query(sparqlQuery)
                .acceptHeader(SPARQL_RESULTS_JSON)
                .timeout(timeoutMs)
                .build()) {

            ResultSet resultSet = queryExecution.execSelect();
            if (!resultSet.hasNext()) return null;
            return resultSet.next().getLiteral("text").getString();
        } catch (Exception e) {
            log.warn("DBpedia query failed for subject {} and predicate {}: {}", subjectUri, predicateUri, e.getMessage());
            return null;
        }
    }

    private RDFNode selectFirstNode(String sparql) {
        try (QueryExecutionHTTP queryExecution = (QueryExecutionHTTP) QueryExecutionHTTPBuilder
                .service(endpoint)
                .query(sparql)
                .acceptHeader(SPARQL_RESULTS_JSON)
                .timeout(timeoutMs)
                .build()) {

            ResultSet resultSet = queryExecution.execSelect();
            if (!resultSet.hasNext()) return null;
            return resultSet.next().get("thumbnail");
        } catch (Exception e) {
            log.warn("DBpedia query failed for var {}: {}", "thumbnail", e.getMessage());
            return null;
        }
    }

    private List<String> selectLiteralStrings(String sparql, String varName) {
        List<String> results = new ArrayList<>();
        try (QueryExecutionHTTP queryExecution = (QueryExecutionHTTP) QueryExecutionHTTPBuilder
                .service(endpoint)
                .query(sparql)
                .acceptHeader(SPARQL_RESULTS_JSON)
                .timeout(timeoutMs)
                .build()) {

            ResultSet resultSet = queryExecution.execSelect();
            while (resultSet.hasNext()) {
                QuerySolution row = resultSet.next();
                RDFNode node = row.get(varName);
                if (node != null && node.isLiteral()) {
                    results.add(node.asLiteral().getString());
                }
            }
        } catch (Exception e) {
            log.warn("DBpedia query failed for var {}: {}", varName, e.getMessage());
        }
        return results;
    }

    private List<String> selectEnglishLabels(String sparql) {
        return selectLiteralStrings(sparql, "label");
    }

    private static List<String> splitList(List<String> rawList) {
        return rawList.stream()
                .flatMap(s -> Arrays.stream(s.split(",")))
                .map(String::trim)
                .filter(x -> !x.isBlank())
                .collect(Collectors.toList());
    }

    private static List<String> removeDuplicates(List<String> inputList) {
        Map<String, String> caseInsensitiveMap = new LinkedHashMap<>();
        inputList.forEach(s -> caseInsensitiveMap.putIfAbsent(s.toLowerCase(), s));
        return new ArrayList<>(caseInsensitiveMap.values());
    }
}
