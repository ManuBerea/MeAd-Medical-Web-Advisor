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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import static com.mead.conditions.enrich.ImageNormalizer.*;
import static com.mead.conditions.config.AsyncConfig.MEAD_EXECUTOR;

@Service
public class ConditionService {

    private static final String SCHEMA_ORG_CONTEXT = "https://schema.org/";
    private static final String CONDITION_TYPE = "MedicalCondition";
    private static final String MEAD_CONDITION_BASE_URL = "https://mead.example/condition/";

    private static final String WIKIDATA_ENTITY_MARKER = "wikidata.org/entity/";
    private static final String DBPEDIA_RESOURCE_MARKER = "dbpedia.org/resource/";
    private static final int MAX_RISK_FACTORS = 12;

    private final ConditionsRepository repo;
    private final WikidataClient wikidata;
    private final DbpediaClient dbpedia;
    private final WikidocSnippetLoader wikidoc;
    private final Executor asyncExecutor;

    public ConditionService(ConditionsRepository repo,
                            WikidataClient wikidata,
                            DbpediaClient dbpedia,
                            WikidocSnippetLoader wikidoc,
                            @Qualifier(MEAD_EXECUTOR) Executor asyncExecutor) {
        this.repo = repo;
        this.wikidata = wikidata;
        this.dbpedia = dbpedia;
        this.wikidoc = wikidoc;
        this.asyncExecutor = asyncExecutor;
    }

    public List<ConditionSummary> list() {
        return repo.findAll().stream()
                .map(c -> new ConditionSummary(c.identifier(), c.name(), c.sameAs()))
                .toList();
    }

    @Cacheable(cacheNames = "conditionDetails", key = "#conditionId")
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

        CompletableFuture<String> overviewFuture = executeAsync(() ->
                wikidoc.fetchOverview(conditionId, condition.name())
        );
        CompletableFuture<List<String>> causesFuture = executeAsync(() ->
                wikidoc.fetchCauses(conditionId, condition.name())
        );
        CompletableFuture<List<String>> riskFactorsFuture = executeAsync(() ->
                wikidoc.fetchRiskFactors(conditionId, condition.name())
        );
        CompletableFuture<List<String>> symptomsFuture = executeAsync(() ->
                wikidoc.fetchSymptoms(conditionId, condition.name())
        );

        CompletableFuture.allOf(
                wikidataFuture,
                dbpediaFuture,
                overviewFuture,
                causesFuture,
                riskFactorsFuture,
                symptomsFuture
        ).join();

        WikidataEnrichment wikidataEnrichment = wikidataFuture.join();
        DbpediaEnrichment dbpediaEnrichment = dbpediaFuture.join();

        String description = pickFirstNotBlank(dbpediaEnrichment.description(), wikidataEnrichment.description());
        List<String> symptoms = normalizeLabels(pickFirstNotEmpty(wikidataEnrichment.symptoms(), dbpediaEnrichment.symptoms()));
        if (symptoms.isEmpty()) {
            symptoms = normalizeLabels(symptomsFuture.join());
        }
        List<String> baseRiskFactors = normalizeLabels(pickFirstNotEmpty(wikidataEnrichment.riskFactors(), dbpediaEnrichment.riskFactors()));
        List<String> causes = causesFuture.join();
        List<String> wikidocRiskFactors = riskFactorsFuture.join();
        List<String> riskFactors = normalizeLabels(mergeUnique(baseRiskFactors, causes, wikidocRiskFactors));
        riskFactors = filterUiRiskFactors(riskFactors);
        riskFactors = limitList(riskFactors, MAX_RISK_FACTORS);
        List<String> images = combineAndNormalizeImages(wikidataEnrichment.images(), dbpediaEnrichment.images());

        String wikidocSnippet = fallbackSnippet(overviewFuture.join());

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
                wikidocSnippet
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

    private static String fallbackSnippet(String snippet) {
        if (snippet != null && !snippet.isBlank()) return snippet;
        return "WikiDoc summary unavailable.";
    }

    private static List<String> mergeUnique(List<String>... lists) {
        if (lists == null) return List.of();
        var map = new java.util.LinkedHashMap<String, String>();
        for (List<String> list : lists) {
            if (list == null) continue;
            for (String value : list) {
                if (value == null || value.isBlank()) continue;
                map.putIfAbsent(value.trim().toLowerCase(), value.trim());
            }
        }
        return new ArrayList<>(map.values());
    }

    private static List<String> normalizeLabels(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            String cleaned = cleanLabel(value);
            if (cleaned != null) normalized.add(cleaned);
        }
        return normalized;
    }

    private static String cleanLabel(String value) {
        if (value == null) return null;
        String trimmed = value.replaceAll("\\s+", " ").trim();
        trimmed = trimmed.replaceAll("\\[[^\\]]*\\]", "").trim();
        trimmed = trimmed.replaceAll("^[^\\p{L}\\p{N}]+", "").trim();
        if (trimmed.isBlank()) return null;
        if (trimmed.length() > 140) {
            trimmed = trimmed.split("\\.\\s+", 2)[0];
        }
        if (trimmed.length() > 140) return null;
        return trimmed;
    }

    private static List<String> limitList(List<String> values, int max) {
        if (values == null || values.isEmpty()) return List.of();
        if (values.size() <= max) return values;
        return new ArrayList<>(values.subList(0, max));
    }

    private static List<String> filterUiRiskFactors(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        List<String> filtered = new ArrayList<>();
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            if (isUiFriendlyRiskFactor(value)) {
                filtered.add(value);
            }
        }
        return filtered;
    }

    private static boolean isUiFriendlyRiskFactor(String value) {
        String lower = value.toLowerCase();
        if (lower.contains(" and ") || lower.contains(" or ")) return false;
        return !value.matches(".*[\\.,;:()\\[\\]].*");
    }
}
