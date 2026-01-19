package com.mead.geography.repository;

import com.mead.geography.rdf.RdfService;
import jakarta.annotation.PostConstruct;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.system.Txn;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RegionsRepository {

    private final RdfService rdf;
    private final Map<String, Region> regionMap = new ConcurrentHashMap<>();
    private volatile List<Region> cachedRegions = List.of();

    public RegionsRepository(RdfService rdf) {
        this.rdf = rdf;
    }

    @PostConstruct
    public void init() {
        refresh();
    }

    public void refresh() {
        List<Region> regions = fetchAllFromRdf();
        regionMap.clear();
        for (Region region : regions) {
            regionMap.put(region.identifier(), region);
        }
        cachedRegions = List.copyOf(regions);
    }

    public record Region(
            String identifier,
            String name,
            String type,
            List<String> sameAs
    ) {}

    public List<Region> findAll() {
        return cachedRegions;
    }

    public Optional<Region> findById(String id) {
        return Optional.ofNullable(regionMap.get(id));
    }

    private List<Region> fetchAllFromRdf() {
        String sparqlQuery = """
                PREFIX schema: <https://schema.org/>
                SELECT ?identifier ?name ?type ?sameAs WHERE {
                  ?region a schema:Place ;
                          schema:identifier ?identifier ;
                          schema:name ?name ;
                          schema:sameAs ?sameAs .
                  OPTIONAL { ?region schema:additionalType ?type . }
                }
                ORDER BY ?identifier
                """;

        return Txn.calculateRead(rdf.getDataset(), () -> {
            Map<String, RegionBuilder> builders = new LinkedHashMap<>();

            try (QueryExecution queryExecution = QueryExecutionFactory.create(sparqlQuery, rdf.getDataset())) {
                ResultSet resultSet = queryExecution.execSelect();
                while (resultSet.hasNext()) {
                    QuerySolution row = resultSet.next();
                    String id = row.getLiteral("identifier").getString();
                    String name = row.getLiteral("name").getString();
                    String sameAs = readNodeValue(row.get("sameAs"));
                    String type = row.contains("type") ? readNodeValue(row.get("type")) : null;

                    builders.computeIfAbsent(id, key -> new RegionBuilder(id, name))
                            .addSameAs(sameAs)
                            .type(type);
                }
            }

            return builders.values().stream()
                    .map(RegionBuilder::build)
                    .toList();
        });
    }

    private static String readNodeValue(RDFNode node) {
        if (node == null) return null;
        if (node.isLiteral()) return node.asLiteral().getString();
        if (node.isResource()) return node.asResource().getURI();
        return node.toString();
    }

    private static class RegionBuilder {
        private final String id;
        private final String name;
        private String type;
        private final List<String> sameAs = new ArrayList<>();

        private RegionBuilder(String id, String name) {
            this.id = id;
            this.name = name;
        }

        RegionBuilder addSameAs(String sameAsValue) {
            if (sameAsValue != null) sameAs.add(sameAsValue);
            return this;
        }

        RegionBuilder type(String typeValue) {
            if (typeValue != null && !typeValue.isBlank()) {
                this.type = typeValue.trim();
            }
            return this;
        }

        Region build() {
            return new Region(
                    id,
                    name,
                    type,
                    List.copyOf(sameAs)
            );
        }
    }
}
