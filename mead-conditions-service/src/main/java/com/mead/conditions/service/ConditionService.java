package com.mead.conditions.service;

import com.mead.conditions.dto.ConditionDto.ConditionDetail;
import com.mead.conditions.dto.ConditionDto.ConditionSummary;
import com.mead.conditions.enrich.DbpediaClient.DbpediaEnrichment;
import com.mead.conditions.enrich.WikidataClient.WikidataEnrichment;
import com.mead.conditions.enrich.WikidocClient.WikidocEnrichment;
import com.mead.conditions.repository.ConditionsRepository;
import com.mead.conditions.enrich.DbpediaClient;
import com.mead.conditions.enrich.WikidataClient;
import com.mead.conditions.enrich.WikidocClient;
import com.mead.conditions.repository.ConditionsRepository.Condition;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static com.mead.conditions.enrich.ImageNormalizer.*;

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
    private final WikidocClient wikidoc;

    public ConditionService(ConditionsRepository repo,
                            WikidataClient wikidata,
                            DbpediaClient dbpedia,
                            WikidocClient wikidoc) {
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

        String wikidataUri = findUriByMarker(condition.sameAs(), WIKIDATA_ENTITY_MARKER);
        String dbpediaUri = findUriByMarker(condition.sameAs(), DBPEDIA_RESOURCE_MARKER);

        CompletableFuture<WikidataEnrichment> wikidataFuture = executeAsync(() ->
                wikidataUri == null
                        ? new WikidataEnrichment(null, List.of(), List.of(), List.of())
                        : wikidata.enrichFromEntityUri(wikidataUri)
        );

        CompletableFuture<DbpediaEnrichment> dbpediaFuture = executeAsync(() ->
                dbpediaUri == null
                        ? new DbpediaEnrichment(null, List.of(), List.of(), List.of())
                        : dbpedia.enrichFromResourceUri(dbpediaUri)
        );

        // WikiDoc enrichment (API with local fallback)
        CompletableFuture<WikidocEnrichment> wikidocFuture = executeAsync(() -> 
                wikidoc.getEnrichment(conditionId, condition.name()));

        CompletableFuture.allOf(wikidataFuture, dbpediaFuture, wikidocFuture).join();

        WikidataEnrichment wikidataEnrichment = wikidataFuture.join();
        DbpediaEnrichment dbpediaEnrichment = dbpediaFuture.join();
        WikidocEnrichment wikidocEnrichment = wikidocFuture.join();

        String description = pickFirstNotBlank(dbpediaEnrichment.description(), wikidataEnrichment.description());
        List<String> symptoms = pickFirstNotEmpty(wikidataEnrichment.symptoms(), dbpediaEnrichment.symptoms());
        List<String> riskFactors = pickFirstNotEmpty(wikidataEnrichment.riskFactors(), dbpediaEnrichment.riskFactors());
        List<String> images = combineAndNormalizeImages(wikidataEnrichment.images(), dbpediaEnrichment.images());

        return new ConditionDetail(
                SCHEMA_ORG_CONTEXT,
                MEAD_CONDITION_BASE_URL + conditionId,
                CONDITION_TYPE,
                condition.identifier(),
                condition.name(),
                description,
                images,
                symptoms,
                riskFactors,
                condition.sameAs(),
                wikidocEnrichment.content(),
                wikidocEnrichment.sourceUrl(),
                wikidocEnrichment.sourceType()
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

    private static List<String> pickFirstNotEmpty(List<String> first, List<String> second) {
        if (first != null && !first.isEmpty()) return first;
        if (second != null && !second.isEmpty()) return second;
        return List.of();
    }

    private static List<String> combineAndNormalizeImages(List<String> firstList, List<String> secondList) {
        List<String> combined = new ArrayList<>();
        if (firstList != null) combined.addAll(firstList);
        if (secondList != null) combined.addAll(secondList);
        return normalize(combined);
    }
}
