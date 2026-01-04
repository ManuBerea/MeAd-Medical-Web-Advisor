package com.mead.conditions.controller;

import com.mead.conditions.service.RdfService;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.system.Txn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/v1/sparql")
public class SparqlController {

    private static final Logger log = LoggerFactory.getLogger(SparqlController.class);

    private static final String APPLICATION_SPARQL_QUERY = "application/sparql-query";
    private static final String APPLICATION_SPARQL_RESULTS_JSON = "application/sparql-results+json";
    private static final String TEXT_TURTLE = "text/turtle";

    private static final int MAX_QUERY_LENGTH = 2000;
    private static final long QUERY_TIMEOUT_MS = 5000;
    private static final boolean REQUIRE_LIMIT = true;

    private final RdfService rdfService;

    public SparqlController(RdfService rdfService) {
        this.rdfService = rdfService;
    }

    @PostMapping(
            consumes = {APPLICATION_SPARQL_QUERY, MediaType.TEXT_PLAIN_VALUE},
            produces = {APPLICATION_SPARQL_RESULTS_JSON, TEXT_TURTLE}
    )
    public ResponseEntity<String> sparqlPost(@RequestBody String queryString) {
        if (queryString != null && queryString.length() > MAX_QUERY_LENGTH) {
            return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE)
                    .body("Query too long. Max allowed: " + MAX_QUERY_LENGTH);
        }
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

        if (REQUIRE_LIMIT && query.isSelectType() && !query.hasLimit()) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("SELECT queries must have a LIMIT clause.");
        }

        // Run queries in a READ transaction.
        return Txn.calculateRead(rdfService.getDataset(), () -> {
            try (QueryExecution queryExecution = QueryExecution.create()
                    .dataset(rdfService.getDataset())
                    .query(query)
                    .timeout(QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .build()) {

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
                log.error("SPARQL execution error: {}", e.getMessage());
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.TEXT_PLAIN)
                        .body("Query execution error:\n" + e.getMessage());
            }
        });
    }
}
