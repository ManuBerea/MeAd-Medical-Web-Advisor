package com.mead.geography.repository;

import com.mead.geography.rdf.RdfService;
import jakarta.annotation.PostConstruct;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.system.Txn;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class IndicatorsRepository {

    private final RdfService rdf;
    private final Map<String, Indicator> indicatorMap = new ConcurrentHashMap<>();
    private volatile List<Indicator> cachedIndicators = List.of();

    public IndicatorsRepository(RdfService rdf) {
        this.rdf = rdf;
    }

    @PostConstruct
    public void init() {
        refresh();
    }

    public void refresh() {
        List<Indicator> indicators = fetchAllFromRdf();
        indicatorMap.clear();
        for (Indicator indicator : indicators) {
            indicatorMap.put(indicator.identifier(), indicator);
        }
        cachedIndicators = List.copyOf(indicators);
    }

    public record Indicator(
            String identifier,
            String name,
            String variableMeasured,
            String measurementTechnique
    ) {}

    public List<Indicator> findAll() {
        return cachedIndicators;
    }

    public Optional<Indicator> findById(String id) {
        return Optional.ofNullable(indicatorMap.get(id));
    }

    private List<Indicator> fetchAllFromRdf() {
        String sparqlQuery = """
                PREFIX schema: <https://schema.org/>
                SELECT ?identifier ?name ?variableMeasured ?measurementTechnique WHERE {
                  ?indicator a schema:Dataset ;
                             schema:identifier ?identifier ;
                             schema:name ?name .
                  OPTIONAL { ?indicator schema:variableMeasured ?variableMeasured . }
                  OPTIONAL { ?indicator schema:measurementTechnique ?measurementTechnique . }
                }
                ORDER BY ?identifier
                """;

        return Txn.calculateRead(rdf.getDataset(), () -> {
            Map<String, IndicatorBuilder> builders = new LinkedHashMap<>();

            try (QueryExecution queryExecution = QueryExecutionFactory.create(sparqlQuery, rdf.getDataset())) {
                ResultSet resultSet = queryExecution.execSelect();
                while (resultSet.hasNext()) {
                    QuerySolution row = resultSet.next();
                    String id = row.getLiteral("identifier").getString();
                    String name = row.getLiteral("name").getString();
                    String variableMeasured = readNodeValue(row.get("variableMeasured"));
                    String measurementTechnique = readNodeValue(row.get("measurementTechnique"));

                    builders.computeIfAbsent(id, key -> new IndicatorBuilder(id, name))
                            .setVariableMeasured(variableMeasured)
                            .setMeasurementTechnique(measurementTechnique);
                }
            }

            return builders.values().stream()
                    .map(IndicatorBuilder::build)
                    .toList();
        });
    }

    private static String readNodeValue(RDFNode node) {
        if (node == null) return null;
        if (node.isLiteral()) return node.asLiteral().getString();
        if (node.isResource()) return node.asResource().getURI();
        return node.toString();
    }

    private static class IndicatorBuilder {
        private final String id;
        private final String name;
        private String variableMeasured;
        private String measurementTechnique;

        IndicatorBuilder(String id, String name) {
            this.id = id;
            this.name = name;
        }

        IndicatorBuilder setVariableMeasured(String variableMeasuredValue) {
            if (this.variableMeasured == null && variableMeasuredValue != null) {
                this.variableMeasured = variableMeasuredValue;
            }
            return this;
        }

        IndicatorBuilder setMeasurementTechnique(String measurementTechniqueValue) {
            if (this.measurementTechnique == null && measurementTechniqueValue != null) {
                this.measurementTechnique = measurementTechniqueValue;
            }
            return this;
        }

        Indicator build() {
            return new Indicator(
                    id,
                    name,
                    variableMeasured,
                    measurementTechnique
            );
        }
    }
}
