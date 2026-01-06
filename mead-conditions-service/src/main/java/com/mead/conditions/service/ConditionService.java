package com.mead.conditions.service;

import com.mead.conditions.dto.ConditionDto.ConditionDetail;
import com.mead.conditions.dto.ConditionDto.ConditionSummary;
import com.mead.conditions.repository.ConditionsRepository.Condition;
import com.mead.conditions.repository.ConditionsRepository;
import com.mead.conditions.enrich.DbpediaClient;
import com.mead.conditions.enrich.WikidataClient;
import com.mead.conditions.enrich.WikidataClient.WikidataEnrichment;
import com.mead.conditions.enrich.WikidocSnippetLoader;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
public class ConditionService {

    private static final String WIKIDATA_ENTITY_MARKER = "wikidata.org/entity/";
    private static final String DBPEDIA_RESOURCE_MARKER = "dbpedia.org/resource/";
    private static final String SCHEMA_ORG_CONTEXT = "https://schema.org/";
    private static final String MEAD_CONDITION_BASE_URL = "https://mead.example/condition/";

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

        CompletableFuture<WikidataEnrichment> wikidataFuture = CompletableFuture.supplyAsync(() ->
                (wikidataUri != null)
                        ? wikidata.enrichFromEntityUri(wikidataUri)
                        : new WikidataEnrichment(null, List.of(), List.of(), null)
        );

        CompletableFuture<String> dbpediaDescFuture = CompletableFuture.supplyAsync(() ->
                (dbpediaUri != null) ? dbpedia.englishDescription(dbpediaUri) : null
        );

        CompletableFuture<List<String>> dbpediaSymptomsFuture = CompletableFuture.supplyAsync(() ->
                (dbpediaUri != null) ? dbpedia.symptoms(dbpediaUri) : List.of()
        );

        CompletableFuture<List<String>> dbpediaRiskFactorsFuture = CompletableFuture.supplyAsync(() ->
                (dbpediaUri != null) ? dbpedia.riskFactorsOrRisks(dbpediaUri) : List.of()
        );

        CompletableFuture<String> dbpediaThumbnailFuture = CompletableFuture.supplyAsync(() ->
                (dbpediaUri != null) ? dbpedia.thumbnailUrl(dbpediaUri) : null
        );

        CompletableFuture<String> snippetFuture = CompletableFuture.supplyAsync(() ->
                wikidoc.loadSnippet(conditionId)
        );

        CompletableFuture.allOf(
                wikidataFuture, dbpediaDescFuture, dbpediaSymptomsFuture,
                dbpediaRiskFactorsFuture, dbpediaThumbnailFuture, snippetFuture
        ).join();

        WikidataEnrichment wikidataEnrichment = wikidataFuture.join();
        String dbpediaText = dbpediaDescFuture.join();

        String description = (dbpediaText != null && !dbpediaText.isBlank())
                ? dbpediaText
                : wikidataEnrichment.description();

        List<String> symptoms = wikidataEnrichment.symptoms();
        if ((symptoms == null || symptoms.isEmpty())) {
            symptoms = dbpediaSymptomsFuture.join();
        }

        List<String> riskFactors = wikidataEnrichment.riskFactors();
        if ((riskFactors == null || riskFactors.isEmpty())) {
            riskFactors = dbpediaRiskFactorsFuture.join();
        }

        String image = wikidataEnrichment.image();
        if ((image == null || image.isBlank())) {
            image = dbpediaThumbnailFuture.join();
        }

        String snippet = snippetFuture.join();

        return new ConditionDetail(
                SCHEMA_ORG_CONTEXT,
                MEAD_CONDITION_BASE_URL + conditionId,
                "MedicalCondition",
                condition.identifier(),
                condition.name(),
                description,
                image,
                symptoms == null ? List.of() : symptoms,
                riskFactors == null ? List.of() : riskFactors,
                condition.sameAs(),
                snippet
        );
    }

    private static String pickSameAs(List<String> sameAsList, String marker) {
        return sameAsList.stream()
                .filter(uri -> uri != null && uri.contains(marker))
                .findFirst()
                .orElse(null);
    }
}
