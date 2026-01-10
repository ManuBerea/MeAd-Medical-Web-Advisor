package com.mead.conditions.service;

import com.mead.conditions.dto.ConditionDto.ConditionDetail;
import com.mead.conditions.dto.ConditionDto.ConditionSummary;
import com.mead.conditions.enrich.DbpediaClient.DbpediaEnrichment;
import com.mead.conditions.enrich.WikidataClient.WikidataEnrichment;
import com.mead.conditions.repository.ConditionsRepository;
import com.mead.conditions.enrich.DbpediaClient;
import com.mead.conditions.enrich.WikidataClient;
import com.mead.conditions.enrich.WikidocSnippetLoader;
import com.mead.conditions.repository.ConditionsRepository.Condition;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

@Service
public class ConditionService {

    private static final String SCHEMA_ORG_CONTEXT = "https://schema.org/";
    private static final String CONDITION_TYPE = "MedicalCondition";
    private static final String MEAD_CONDITION_BASE_URL = "https://mead.example/condition/";

    private static final String WIKIDATA_ENTITY_MARKER = "wikidata.org/entity/";
    private static final String DBPEDIA_RESOURCE_MARKER = "dbpedia.org/resource/";

    private final ConditionsRepository repo;
    private final WikidataClient wikidata;
    private final DbpediaClient dbpedia;
    private final WikidocSnippetLoader wikidoc;

    public ConditionService(ConditionsRepository repo,
                            WikidataClient wikidata,
                            DbpediaClient dbpedia,
                            WikidocSnippetLoader wikidoc) {
        this.repo = repo;
        this.wikidata = wikidata;
        this.dbpedia = dbpedia;
        this.wikidoc = wikidoc;
    }

    public List<ConditionSummary> list() {
        return repo.findAll().stream()
                .map(c -> new ConditionSummary(c.identifier(), c.name(), c.sameAs()))
                .toList();
    }

    public ConditionDetail get(String conditionId) {
        Condition condition = repo.findById(conditionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown condition: " + conditionId));

        String wikidataUri = pickSameAs(condition.sameAs(), WIKIDATA_ENTITY_MARKER);
        String dbpediaUri = pickSameAs(condition.sameAs(), DBPEDIA_RESOURCE_MARKER);

        CompletableFuture<WikidataEnrichment> wdFuture = async(() ->
                wikidataUri == null
                        ? new WikidataEnrichment(null, List.of(), List.of(), List.of())
                        : wikidata.enrichFromEntityUri(wikidataUri)
        );

        CompletableFuture<DbpediaEnrichment> dbFuture = async(() ->
                dbpediaUri == null
                        ? new DbpediaEnrichment(null, List.of(), List.of(), List.of())
                        : dbpedia.enrichFromResourceUri(dbpediaUri)
        );

        CompletableFuture<String> snippetFuture = async(() -> wikidoc.loadSnippet(conditionId));

        CompletableFuture.allOf(wdFuture, dbFuture, snippetFuture).join();

        WikidataEnrichment wd = wdFuture.join();
        DbpediaEnrichment db = dbFuture.join();

        String description = preferText(db.description(), wd.description());
        List<String> symptoms = preferList(wd.symptoms(), db.symptoms());
        List<String> riskFactors = preferList(wd.riskFactors(), db.riskFactors());
        List<String> images = mergeImages(wd.images(), db.images());
        String image = images.isEmpty() ? null : images.get(0);

        return new ConditionDetail(
                SCHEMA_ORG_CONTEXT,
                MEAD_CONDITION_BASE_URL + conditionId,
                CONDITION_TYPE,
                condition.identifier(),
                condition.name(),
                description,
                image,
                images,
                symptoms,
                riskFactors,
                condition.sameAs(),
                snippetFuture.join()
        );
    }

    private static <T> CompletableFuture<T> async(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier);
    }

    private static String pickSameAs(List<String> sameAsList, String marker) {
        return sameAsList.stream()
                .filter(uri -> uri != null && uri.contains(marker))
                .findFirst()
                .orElse(null);
    }

    private static String preferText(String first, String second) {
        if (first != null && !first.isBlank()) return first;
        return second;
    }

    private static List<String> preferList(List<String> first, List<String> second) {
        if (first != null && !first.isEmpty()) return first;
        if (second != null && !second.isEmpty()) return second;
        return List.of();
    }

    private static List<String> mergeImages(List<String> first, List<String> second) {
        Map<String, String> map = new LinkedHashMap<>();
        addImages(map, first);
        addImages(map, second);
        return List.copyOf(map.values());
    }

    private static void addImages(Map<String, String> map, List<String> images) {
        if (images == null) return;
        for (String image : images) {
            if (image == null || image.isBlank()) continue;
            map.putIfAbsent(image.toLowerCase(), image);
        }
    }
}
