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
            Map<String, ConditionBuilder> builders = new LinkedHashMap<>();

            try (QueryExecution queryExecution = QueryExecutionFactory.create(sparqlQuery, rdf.getDataset())) {
                ResultSet resultSet = queryExecution.execSelect();
                while (resultSet.hasNext()) {
                    QuerySolution row = resultSet.next();
                    String id = row.getLiteral("identifier").getString();
                    String name = row.getLiteral("name").getString();
                    String sameAs = readNodeValue(row.get("sameAs"));

                    builders.computeIfAbsent(id, k -> new ConditionBuilder(id, name))
                            .addSameAs(sameAs);
                }
            }

            return builders.values().stream()
                    .map(ConditionBuilder::build)
                    .toList();
        });
    }

    private static String readNodeValue(RDFNode node) {
        if (node == null) return null;
        return node.isResource() ? node.asResource().getURI() : node.toString();
    }

    private static class ConditionBuilder {
        private final String id;
        private final String name;
        private final List<String> sameAsList = new ArrayList<>();

        ConditionBuilder(String id, String name) {
            this.id = id;
            this.name = name;
        }

        void addSameAs(String sameAs) {
            if (sameAs != null) sameAsList.add(sameAs);
        }

        Condition build() {
            return new Condition(id, name, List.copyOf(sameAsList));
        }
    }
}
