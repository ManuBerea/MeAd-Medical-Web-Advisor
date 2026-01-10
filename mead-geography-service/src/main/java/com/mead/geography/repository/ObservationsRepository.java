package com.mead.geography.repository;

import com.mead.geography.rdf.RdfService;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.system.Txn;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ObservationsRepository {

    private final RdfService rdf;

    public ObservationsRepository(RdfService rdf) {
        this.rdf = rdf;
    }

    public record Observation(
            String id,
            String observationDate,
            Double value,
            String unitText
    ) {}

    public List<Observation> findByRegionAndIndicator(String regionUri, String indicatorUri) {
        String sparqlQuery = """
                PREFIX schema: <https://schema.org/>
                SELECT ?obs ?date ?value ?unit WHERE {
                  ?obs a schema:Observation ;
                       schema:observationAbout <%s> ;
                       schema:measuredProperty <%s> ;
                       schema:observationDate ?date ;
                       schema:value ?valueNode .
                  OPTIONAL {
                    ?valueNode schema:value ?valueFromNode .
                    OPTIONAL { ?valueNode schema:unitText ?unit . }
                  }
                  BIND(COALESCE(?valueFromNode, ?valueNode) AS ?value)
                }
                ORDER BY ?date
                """.formatted(regionUri, indicatorUri);

        return Txn.calculateRead(rdf.getDataset(), () -> {
            List<Observation> observations = new ArrayList<>();
            try (QueryExecution queryExecution = QueryExecutionFactory.create(sparqlQuery, rdf.getDataset())) {
                ResultSet resultSet = queryExecution.execSelect();
                while (resultSet.hasNext()) {
                    QuerySolution row = resultSet.next();
                    String id = readNodeValue(row.get("obs"));
                    String date = readNodeValue(row.get("date"));
                    Double value = readLiteralAsDouble(row.get("value"));
                    String unit = readNodeValue(row.get("unit"));
                    observations.add(new Observation(id, date, value, unit));
                }
            }
            return observations;
        });
    }

    private static String readNodeValue(RDFNode node) {
        if (node == null) return null;
        if (node.isLiteral()) return node.asLiteral().getString();
        if (node.isResource()) return node.asResource().getURI();
        return node.toString();
    }

    private static Double readLiteralAsDouble(RDFNode node) {
        if (node == null || !node.isLiteral()) return null;
        try {
            return node.asLiteral().getDouble();
        } catch (Exception e) {
            try {
                return Double.parseDouble(node.asLiteral().getString());
            } catch (Exception ignored) {
                return null;
            }
        }
    }
}
