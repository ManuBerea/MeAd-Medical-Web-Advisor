package com.mead.conditions.data;

import com.mead.conditions.rdf.RdfService;
import jakarta.annotation.PostConstruct;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.system.Txn;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LocalConditionsRepository {

    private final RdfService rdf;
    private final Map<String, LocalCondition> conditionsMap = new ConcurrentHashMap<>();
    private volatile List<LocalCondition> cachedAll = List.of();

    public LocalConditionsRepository(RdfService rdf) {
        this.rdf = rdf;
    }

    @PostConstruct
    public void init() {
        refresh();
    }

    public void refresh() {
        List<LocalCondition> all = fetchAllFromRdf();
        conditionsMap.clear();
        for (LocalCondition c : all) {
            conditionsMap.put(c.identifier(), c);
        }
        cachedAll = List.copyOf(all);
    }

    public record LocalCondition(
            String identifier,
            String name,
            List<String> sameAs
    ) {}

    public List<LocalCondition> findAll() {
        return cachedAll;
    }

    public Optional<LocalCondition> findById(String id) {
        return Optional.ofNullable(conditionsMap.get(id));
    }

    private List<LocalCondition> fetchAllFromRdf() {
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
}
