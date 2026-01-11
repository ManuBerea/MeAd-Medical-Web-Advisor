package com.mead.geography.service;

import com.mead.geography.dto.GeographyDto.RegionDetail;
import com.mead.geography.dto.GeographyDto.RegionSummary;
import com.mead.geography.enrich.DbpediaClient;
import com.mead.geography.enrich.DbpediaClient.DbpediaEnrichment;
import com.mead.geography.enrich.ImageNormalizer;
import com.mead.geography.enrich.WikidataClient;
import com.mead.geography.enrich.WikidataClient.WikidataEnrichment;
import com.mead.geography.enrich.WikidocSnippetLoader;
import com.mead.geography.repository.RegionsRepository;
import com.mead.geography.repository.RegionsRepository.Region;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

@Service
public class GeographyService {

    private static final String SCHEMA_ORG_CONTEXT = "https://schema.org/";
    private static final String PLACE_TYPE = "Place";
    private static final String MEAD_REGION_BASE_URL = "https://mead.example/region/";

    private static final String WIKIDATA_ENTITY_MARKER = "wikidata.org/entity/";
    private static final String DBPEDIA_RESOURCE_MARKER = "dbpedia.org/resource/";

    private final RegionsRepository repo;
    private final WikidataClient wikidata;
    private final DbpediaClient dbpedia;
    private final WikidocSnippetLoader wikidoc;

    public GeographyService(RegionsRepository repo,
                            WikidataClient wikidata,
                            DbpediaClient dbpedia,
                            WikidocSnippetLoader wikidoc) {
        this.repo = repo;
        this.wikidata = wikidata;
        this.dbpedia = dbpedia;
        this.wikidoc = wikidoc;
    }

    public List<RegionSummary> listRegions() {
        return repo.findAll().stream()
                .map(r -> new RegionSummary(r.identifier(), r.name(), r.sameAs()))
                .toList();
    }

    public RegionDetail getRegion(String regionId) {
        Region region = repo.findById(regionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown region: " + regionId));

        String wikidataUri = findUriByMarker(region.sameAs(), WIKIDATA_ENTITY_MARKER);
        String dbpediaUri = findUriByMarker(region.sameAs(), DBPEDIA_RESOURCE_MARKER);

        CompletableFuture<WikidataEnrichment> wikidataFuture = executeAsync(() ->
                wikidataUri == null
                        ? new WikidataEnrichment(null, null, null, List.of(), List.of(), List.of(), List.of())
                        : wikidata.enrichFromEntityUri(wikidataUri)
        );

        CompletableFuture<DbpediaEnrichment> dbpediaFuture = executeAsync(() ->
                dbpediaUri == null
                        ? new DbpediaEnrichment(null, null, null, List.of(), List.of(), List.of(), List.of())
                        : dbpedia.enrichFromResourceUri(dbpediaUri)
        );

        CompletableFuture<String> snippetFuture = executeAsync(() -> wikidoc.loadSnippet(regionId));

        CompletableFuture.allOf(wikidataFuture, dbpediaFuture, snippetFuture).join();

        WikidataEnrichment wikidataEnrichment = wikidataFuture.join();
        DbpediaEnrichment dbpediaEnrichment = dbpediaFuture.join();

        String description = pickFirstNotBlank(dbpediaEnrichment.description(), wikidataEnrichment.description());
        String populationTotal = pickFirstNotBlank(wikidataEnrichment.populationTotal(), dbpediaEnrichment.populationTotal());
        String populationDensity = pickFirstNotBlank(dbpediaEnrichment.populationDensity(), wikidataEnrichment.populationDensity());
        List<String> climates = mergeUnique(dbpediaEnrichment.climates(), wikidataEnrichment.climates());
        List<String> industrial = mergeUnique(dbpediaEnrichment.industries(), wikidataEnrichment.industries());
        List<String> cultural = mergeUnique(dbpediaEnrichment.culturalFactors(), wikidataEnrichment.culturalFactors());
        List<String> images = combineAndNormalizeImages(wikidataEnrichment.images(), dbpediaEnrichment.images());

        return new RegionDetail(
                SCHEMA_ORG_CONTEXT,
                MEAD_REGION_BASE_URL + region.identifier(),
                PLACE_TYPE,
                region.identifier(),
                region.name(),
                description,
                populationTotal,
                populationDensity,
                climates,
                industrial,
                cultural,
                images,
                region.sameAs(),
                snippetFuture.join()
        );
    }

    private static <T> CompletableFuture<T> executeAsync(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier);
    }

    private static String findUriByMarker(List<String> sameAsList, String marker) {
        return sameAsList.stream()
                .filter(uri -> uri != null && uri.contains(marker))
                .findFirst()
                .orElse(null);
    }

    private static String pickFirstNotBlank(String first, String second) {
        if (first != null && !first.isBlank()) return first;
        return second;
    }

    private static List<String> mergeUnique(List<String> first, List<String> second) {
        Map<String, String> map = new LinkedHashMap<>();
        addAllNormalized(map, first);
        addAllNormalized(map, second);
        return new ArrayList<>(map.values());
    }

    private static void addAllNormalized(Map<String, String> map, List<String> values) {
        if (values == null) return;
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                map.putIfAbsent(value.toLowerCase(), value);
            }
        }
    }

    private static List<String> combineAndNormalizeImages(List<String> firstList, List<String> secondList) {
        List<String> combined = new ArrayList<>();
        if (firstList != null) combined.addAll(firstList);
        if (secondList != null) combined.addAll(secondList);
        return ImageNormalizer.normalize(combined);
    }
}
