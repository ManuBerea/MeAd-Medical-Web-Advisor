package com.mead.conditions.enrich;

import org.apache.jena.query.QuerySolution;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.sparql.exec.http.QueryExecutionHTTP;
import org.apache.jena.sparql.exec.http.QueryExecutionHTTPBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Component
public class SparqlHttpClient {

    private static final Logger log = LoggerFactory.getLogger(SparqlHttpClient.class);

    public static final String ACCEPT_SPARQL_RESULTS_JSON = "application/sparql-results+json";
    public static final String HEADER_USER_AGENT = "User-Agent";

    public List<String> selectStrings(SelectRequest request) {
        return runSelect(request, row -> readNodeAsString(row.get(request.varName())));
    }

    public String selectFirstString(SelectRequest request) {
        List<String> values = selectStrings(request);
        return values.isEmpty() ? null : values.get(0);
    }

    public record SelectRequest(
            String endpoint,
            long timeoutMs,
            Map<String, String> headers,
            String sparql,
            String varName,
            String sourceTag
    ) {}

    private List<String> runSelect(SelectRequest request, Function<QuerySolution, String> mapper) {
        List<String> results = new ArrayList<>();

        try {
            QueryExecutionHTTPBuilder builder = (QueryExecutionHTTPBuilder) QueryExecutionHTTPBuilder
                    .service(request.endpoint())
                    .query(request.sparql())
                    .acceptHeader(ACCEPT_SPARQL_RESULTS_JSON)
                    .timeout(request.timeoutMs());

            Map<String, String> headers = safeHeaders(request.headers());
            headers.forEach(builder::httpHeader);

            try (QueryExecutionHTTP qexec = builder.build()) {
                ResultSet resultSet = qexec.execSelect();
                while (resultSet.hasNext()) {
                    String value = mapper.apply(resultSet.next());
                    if (value != null && !value.isBlank()) results.add(value);
                }
            }

        } catch (Exception e) {
            log.warn("{} query failed: {}", request.sourceTag(), e.getMessage());
        }

        return results;
    }

    private static Map<String, String> safeHeaders(Map<String, String> headers) {
        return headers == null ? Map.of() : headers;
    }

    private static String readNodeAsString(RDFNode node) {
        if (node == null) return null;
        if (node.isLiteral()) return node.asLiteral().getString();
        if (node.isResource()) return node.asResource().getURI();
        return node.toString();
    }
}
