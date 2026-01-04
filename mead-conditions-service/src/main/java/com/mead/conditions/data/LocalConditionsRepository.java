package com.mead.conditions.data;

import com.mead.conditions.rdf.RdfService;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.system.Txn;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class LocalConditionsRepository {

    private final RdfService rdf;

    public LocalConditionsRepository(RdfService rdf) {
        this.rdf = rdf;
    }

    public record LocalCondition(
            String identifier,
            String name,
            List<String> sameAs
    ) {}

    public List<LocalCondition> findAll() {
        String sparqlQuery = """
                PREFIX schema: <https://schema.org/>
                SELECT ?identifier ?name ?sameAs WHERE {
                  ?condition a schema:MedicalCondition ;
                             schema:identifier ?identifier ;
                             schema:name ?name ;
                             schema:sameAs ?sameAs .
                }
                ORDER BY ?identifier
                """;

        return Txn.calculateRead(rdf.getDataset(), () -> {
            Map<String, String> identifierToNameMap = new LinkedHashMap<>();
            Map<String, List<String>> identifierToSameAsMap = new LinkedHashMap<>();

            try (QueryExecution queryExecution = QueryExecutionFactory.create(sparqlQuery, rdf.getDataset())) {
                ResultSet resultSet = queryExecution.execSelect();
                while (resultSet.hasNext()) {
                    QuerySolution row = resultSet.next();
                    String identifier = row.getLiteral("identifier").getString();
                    String name = row.getLiteral("name").getString();

                    RDFNode sameAsNode = row.get("sameAs");
                    String sameAsUri = sameAsNode.isResource()
                            ? sameAsNode.asResource().getURI()
                            : sameAsNode.toString();

                    identifierToNameMap.putIfAbsent(identifier, name);
                    identifierToSameAsMap.computeIfAbsent(identifier, k -> new ArrayList<>()).add(sameAsUri);
                }
            }

            List<LocalCondition> localConditions = new ArrayList<>();
            for (String identifier : identifierToNameMap.keySet()) {
                localConditions.add(new LocalCondition(
                        identifier,
                        identifierToNameMap.get(identifier),
                        List.copyOf(identifierToSameAsMap.getOrDefault(identifier, List.of()))
                ));
            }
            return localConditions;
        });
    }

    public Optional<LocalCondition> findById(String id) {
        return findAll().stream().filter(condition -> condition.identifier().equals(id)).findFirst();
    }
}
