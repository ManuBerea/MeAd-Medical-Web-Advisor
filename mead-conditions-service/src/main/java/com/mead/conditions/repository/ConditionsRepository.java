package com.mead.conditions.repository;

import com.mead.conditions.service.RdfService;
import jakarta.annotation.PostConstruct;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.system.Txn;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ConditionsRepository {

    private final RdfService rdf;
    private final Map<String, Condition> conditionsMap = new ConcurrentHashMap<>();
    private volatile List<Condition> cachedConditions = List.of();

    public ConditionsRepository(RdfService rdf) {
        this.rdf = rdf;
    }

    @PostConstruct
    public void init() {
        refresh();
    }

    public void refresh() {
        List<Condition> conditions = fetchAllFromRdf();
        conditionsMap.clear();
        for (Condition condition : conditions) {
            conditionsMap.put(condition.identifier(), condition);
        }
        cachedConditions = List.copyOf(conditions);
    }

    public record Condition(
            String identifier,
            String name,
            List<String> sameAs
    ) {}

    public List<Condition> findAll() {
        return cachedConditions;
    }

    public Optional<Condition> findById(String id) {
        return Optional.ofNullable(conditionsMap.get(id));
    }

    private List<Condition> fetchAllFromRdf() {
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

            List<Condition> conditions = new ArrayList<>();
            for (String identifier : identifierToNameMap.keySet()) {
                conditions.add(new Condition(
                        identifier,
                        identifierToNameMap.get(identifier),
                        List.copyOf(identifierToSameAsMap.getOrDefault(identifier, List.of()))
                ));
            }
            return conditions;
        });
    }
}
