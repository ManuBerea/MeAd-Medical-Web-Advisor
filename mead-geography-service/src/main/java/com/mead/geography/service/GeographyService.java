package com.mead.geography.service;

import com.mead.geography.dto.GeographyDto.RegionDetail;
import com.mead.geography.dto.GeographyDto.RegionSummary;
import com.mead.geography.enrich.DbpediaClient;
import com.mead.geography.enrich.DbpediaClient.DbpediaEnrichment;
import com.mead.geography.enrich.ImageNormalizer;
import com.mead.geography.enrich.WikidataClient;
import com.mead.geography.enrich.WikidataClient.WikidataEnrichment;
import com.mead.geography.enrich.WikipediaSummaryLoader;
import com.mead.geography.repository.RegionsRepository;
import com.mead.geography.repository.RegionsRepository.Region;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import static com.mead.geography.config.AsyncConfig.MEAD_EXECUTOR;

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
    private final WikipediaSummaryLoader wikipedia;
    private final Executor asyncExecutor;

    public GeographyService(RegionsRepository repo,
                            WikidataClient wikidata,
                            DbpediaClient dbpedia,
                            WikipediaSummaryLoader wikipedia,
                            @Qualifier(MEAD_EXECUTOR) Executor asyncExecutor) {
        this.repo = repo;
        this.wikidata = wikidata;
        this.dbpedia = dbpedia;
        this.wikipedia = wikipedia;
        this.asyncExecutor = asyncExecutor;
    }

    public List<RegionSummary> listRegions() {
        List<CompletableFuture<RegionSummary>> summaries = repo.findAll().stream()
                .map(region -> CompletableFuture.supplyAsync(() -> toSummaryIfEligible(region), asyncExecutor))
                .toList();

        return summaries.stream()
                .map(CompletableFuture::join)
                .filter(Objects::nonNull)
                .toList();
    }

    @Cacheable(cacheNames = "regionDetails", key = "#regionId")
    public RegionDetail getRegion(String regionId) {
        Region region = repo.findById(regionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown region: " + regionId));

        String wikidataUri = findUriByMarker(region.sameAs(), WIKIDATA_ENTITY_MARKER);
        String dbpediaUri = findUriByMarker(region.sameAs(), DBPEDIA_RESOURCE_MARKER);

        CompletableFuture<WikidataEnrichment> wikidataFuture = executeAsync(() ->
                wikidataUri == null
                        ? new WikidataEnrichment(null, null, null, List.of(), List.of())
                        : wikidata.enrichFromEntityUri(wikidataUri)
        );

        CompletableFuture<DbpediaEnrichment> dbpediaFuture = executeAsync(() ->
                dbpediaUri == null
                        ? new DbpediaEnrichment(null, null, null, List.of(), List.of())
                        : dbpedia.enrichFromResourceUri(dbpediaUri)
        );

        CompletableFuture<String> summaryFuture = executeAsync(() -> wikipedia.loadSummary(regionId, region.name()));

        CompletableFuture.allOf(wikidataFuture, dbpediaFuture, summaryFuture).join();

        WikidataEnrichment wikidataEnrichment = wikidataFuture.join();
        DbpediaEnrichment dbpediaEnrichment = dbpediaFuture.join();

        String description = pickFirstNotBlank(dbpediaEnrichment.description(), wikidataEnrichment.description());
        String populationTotal = pickFirstNumeric(wikidataEnrichment.populationTotal(), dbpediaEnrichment.populationTotal());
        String populationDensity = pickFirstNumeric(dbpediaEnrichment.populationDensity(), wikidataEnrichment.populationDensity());
        List<String> cultural = mergeUnique(dbpediaEnrichment.culturalFactors(), wikidataEnrichment.culturalFactors());
        List<String> images = combineAndNormalizeImages(wikidataEnrichment.images(), dbpediaEnrichment.images());

        String wikipediaSnippet = fallbackSnippet(summaryFuture.join());

        return new RegionDetail(
                SCHEMA_ORG_CONTEXT,
                MEAD_REGION_BASE_URL + region.identifier(),
                resolveRegionType(region.type(), region.sameAs()),
                region.identifier(),
                region.name(),
                description,
                populationTotal,
                populationDensity,
                cultural,
                images,
                region.sameAs(),
                wikipediaSnippet
        );
    }

    private <T> CompletableFuture<T> executeAsync(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, asyncExecutor);
    }

    private static String findUriByMarker(List<String> sameAsList, String marker) {
        return sameAsList.stream()
                .filter(uri -> uri != null && uri.contains(marker))
                .findFirst()
                .orElse(null);
    }

    private String resolveRegionType(String preferredType, List<String> sameAsList) {
        if (preferredType != null && !preferredType.isBlank()) {
            return preferredType;
        }
        String wikidataUri = findUriByMarker(sameAsList, WIKIDATA_ENTITY_MARKER);
        String type = wikidataUri == null ? null : wikidata.fetchRegionType(wikidataUri);
        return type == null ? PLACE_TYPE : type;
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

    private static String fallbackSnippet(String snippet) {
        if (snippet != null && !snippet.isBlank()) return snippet;
        return "Wikipedia summary unavailable.";
    }

    private boolean isNumeric(String value) {
        if (value == null) return false;
        String normalized = value.replace(",", "").trim();
        if (normalized.isBlank()) return false;
        try {
            Double.parseDouble(normalized);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String pickFirstNumeric(String first, String second) {
        if (isNumeric(first)) return first;
        if (isNumeric(second)) return second;
        return pickFirstNotBlank(first, second);
    }

    private RegionSummary toSummaryIfEligible(Region region) {
        if (region.type() == null || region.type().isBlank()) {
            return null;
        }
        return new RegionSummary(
            region.identifier(),
            region.name(),
            resolveRegionType(region.type(), region.sameAs()),
            region.sameAs()
        );
    }

}
