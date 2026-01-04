package com.mead.geography.controller;

import com.mead.geography.rdf.RdfService;
import org.apache.jena.query.*;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.system.Txn;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/v1/sparql")
public class SparqlController {

    private final RdfService rdfService;

    public SparqlController(RdfService rdfService) {
        this.rdfService = rdfService;
    }

    // POST /api/sparql with raw SPARQL in body
    // curl -X POST --data-binary 'SELECT ...' -H 'Content-Type: application/sparql-query'
    @PostMapping(
            consumes = {"application/sparql-query", MediaType.TEXT_PLAIN_VALUE},
            produces = {"application/sparql-results+json", "text/turtle"}
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
            try (QueryExecution qexec = QueryExecutionFactory.create(query, rdfService.getDataset())) {

                if (query.isSelectType()) {
                    ResultSet rs = qexec.execSelect();
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    ResultSetFormatter.outputAsJSON(out, rs);
                    return ResponseEntity.ok()
                            .contentType(MediaType.valueOf("application/sparql-results+json"))
                            .body(out.toString(StandardCharsets.UTF_8));
                }

                if (query.isAskType()) {
                    boolean value = qexec.execAsk();
                    // Minimal SPARQL JSON for ASK:
                    String json = "{\"head\":{},\"boolean\":" + value + "}";
                    return ResponseEntity.ok()
                            .contentType(MediaType.valueOf("application/sparql-results+json"))
                            .body(json);
                }

                // If you ever run CONSTRUCT/DESCRIBE, return Turtle (RDF graph), not SPARQL-results JSON.
                if (query.isConstructType()) {
                    var model = qexec.execConstruct();
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    RDFDataMgr.write(out, model, Lang.TURTLE);
                    return ResponseEntity.ok()
                            .contentType(MediaType.valueOf("text/turtle"))
                            .body(out.toString(StandardCharsets.UTF_8));
                }

                if (query.isDescribeType()) {
                    var model = qexec.execDescribe();
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    RDFDataMgr.write(out, model, Lang.TURTLE);
                    return ResponseEntity.ok()
                            .contentType(MediaType.valueOf("text/turtle"))
                            .body(out.toString(StandardCharsets.UTF_8));
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
