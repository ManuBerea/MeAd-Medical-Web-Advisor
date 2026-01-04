package com.mead.conditions.controller;

import com.mead.conditions.rdf.RdfService;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QueryParseException;
import org.apache.jena.query.ResultSet;
import org.apache.jena.query.ResultSetFormatter;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.system.Txn;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/v1/sparql")
public class SparqlController {

    private static final String APPLICATION_SPARQL_QUERY = "application/sparql-query";
    private static final String APPLICATION_SPARQL_RESULTS_JSON = "application/sparql-results+json";
    private static final String TEXT_TURTLE = "text/turtle";

    private final RdfService rdfService;

    public SparqlController(RdfService rdfService) {
        this.rdfService = rdfService;
    }

    @PostMapping(
            consumes = {APPLICATION_SPARQL_QUERY, MediaType.TEXT_PLAIN_VALUE},
            produces = {APPLICATION_SPARQL_RESULTS_JSON, TEXT_TURTLE}
    )
    public ResponseEntity<String> sparqlPost(@RequestBody String queryString) {
        return execute(queryString);
    }

    private ResponseEntity<String> execute(String queryString) {
        final Query query;
        try {
            query = QueryFactory.create(queryString);
        } catch (QueryParseException e) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("SPARQL parse error:\n" + e.getMessage());
        }

        // Run queries in a READ transaction.
        return Txn.calculateRead(rdfService.getDataset(), () -> {
            try (QueryExecution queryExecution = QueryExecutionFactory.create(query, rdfService.getDataset())) {

                if (query.isSelectType()) {
                    ResultSet resultSet = queryExecution.execSelect();
                    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                    ResultSetFormatter.outputAsJSON(outputStream, resultSet);
                    return ResponseEntity.ok()
                            .contentType(MediaType.valueOf(APPLICATION_SPARQL_RESULTS_JSON))
                            .body(outputStream.toString(StandardCharsets.UTF_8));
                }

                if (query.isAskType()) {
                    boolean result = queryExecution.execAsk();
                    // Minimal SPARQL JSON for ASK:
                    String json = "{\"head\":{},\"boolean\":" + result + "}";
                    return ResponseEntity.ok()
                            .contentType(MediaType.valueOf(APPLICATION_SPARQL_RESULTS_JSON))
                            .body(json);
                }

                // If you ever run CONSTRUCT/DESCRIBE, return Turtle (RDF graph), not SPARQL-results JSON.
                if (query.isConstructType()) {
                    Model model = queryExecution.execConstruct();
                    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                    RDFDataMgr.write(outputStream, model, Lang.TURTLE);
                    return ResponseEntity.ok()
                            .contentType(MediaType.valueOf(TEXT_TURTLE))
                            .body(outputStream.toString(StandardCharsets.UTF_8));
                }

                if (query.isDescribeType()) {
                    Model model = queryExecution.execDescribe();
                    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                    RDFDataMgr.write(outputStream, model, Lang.TURTLE);
                    return ResponseEntity.ok()
                            .contentType(MediaType.valueOf(TEXT_TURTLE))
                            .body(outputStream.toString(StandardCharsets.UTF_8));
                }

                return ResponseEntity.badRequest()
                        .contentType(MediaType.TEXT_PLAIN)
                        .body("Unsupported query type.");
            } catch (Exception e) {
                return ResponseEntity.internalServerError()
                        .contentType(MediaType.TEXT_PLAIN)
                        .body("Query execution error:\n" + e.getMessage());
            }
        });
    }
}
