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
        String q = """
            PREFIX schema: <https://schema.org/>
            SELECT ?identifier ?name ?sameAs WHERE {
              ?c a schema:MedicalCondition ;
                 schema:identifier ?identifier ;
                 schema:name ?name ;
                 schema:sameAs ?sameAs .
            }
            ORDER BY ?identifier
            """;

        return Txn.calculateRead(rdf.getDataset(), () -> {
            Map<String, String> idToName = new LinkedHashMap<>();
            Map<String, List<String>> idToSameAs = new LinkedHashMap<>();

            try (QueryExecution exec = QueryExecutionFactory.create(q, rdf.getDataset())) {
                ResultSet rs = exec.execSelect();
                while (rs.hasNext()) {
                    QuerySolution row = rs.next();
                    String id = row.getLiteral("identifier").getString();
                    String name = row.getLiteral("name").getString();

                    RDFNode sameAsNode = row.get("sameAs");
                    String sameAs = sameAsNode.isResource()
                            ? sameAsNode.asResource().getURI()
                            : sameAsNode.toString();

                    idToName.putIfAbsent(id, name);
                    idToSameAs.computeIfAbsent(id, k -> new ArrayList<>()).add(sameAs);
                }
            }

            List<LocalCondition> out = new ArrayList<>();
            for (String id : idToName.keySet()) {
                out.add(new LocalCondition(
                        id,
                        idToName.get(id),
                        List.copyOf(idToSameAs.getOrDefault(id, List.of()))
                ));
            }
            return out;
        });
    }

    public Optional<LocalCondition> findById(String id) {
        return findAll().stream().filter(c -> c.identifier().equals(id)).findFirst();
    }
}
