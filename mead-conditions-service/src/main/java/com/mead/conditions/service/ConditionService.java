package com.mead.conditions.service;

import com.mead.conditions.data.LocalConditionsRepository.LocalCondition;
import com.mead.conditions.dto.ConditionDtos;
import com.mead.conditions.data.LocalConditionsRepository;
import com.mead.conditions.enrich.DbpediaClient;
import com.mead.conditions.enrich.WikidataClient;
import com.mead.conditions.enrich.WikidataClient.WikidataEnrichment;
import com.mead.conditions.enrich.WikidocSnippetLoader;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ConditionService {

    private static final String WIKIDATA_ENTITY_MARKER = "wikidata.org/entity/";
    private static final String DBPEDIA_RESOURCE_MARKER = "dbpedia.org/resource/";
    private static final String SCHEMA_ORG_CONTEXT = "https://schema.org/";
    private static final String MEAD_CONDITION_BASE_URL = "https://mead.example/condition/";

    private final LocalConditionsRepository repo;
    private final WikidataClient wikidata;
    private final DbpediaClient dbpedia;
    private final WikidocSnippetLoader wikidoc;

    public ConditionService(LocalConditionsRepository repo,
                            WikidataClient wikidata,
                            DbpediaClient dbpedia,
                            WikidocSnippetLoader wikidoc) {
        this.repo = repo;
        this.wikidata = wikidata;
        this.dbpedia = dbpedia;
        this.wikidoc = wikidoc;
    }

    public List<ConditionDtos.ConditionSummary> list() {
        return repo.findAll().stream()
                .map(c -> new ConditionDtos.ConditionSummary(c.identifier(), c.name(), c.sameAs()))
                .toList();
    }

    public ConditionDtos.ConditionDetail get(String conditionId) {
        LocalCondition localCondition = repo.findById(conditionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown condition: " + conditionId));

        String wikidataUri = pickSameAs(localCondition.sameAs(), WIKIDATA_ENTITY_MARKER);
        String dbpediaUri = pickSameAs(localCondition.sameAs(), DBPEDIA_RESOURCE_MARKER);

        WikidataEnrichment wikidataEnrichment = (wikidataUri != null)
                ? wikidata.enrichFromEntityUri(wikidataUri)
                : new WikidataEnrichment(null, List.of(), List.of(), null);

        String dbpediaText = (dbpediaUri != null) ? dbpedia.englishDescription(dbpediaUri) : null;
        String description = (dbpediaText != null && !dbpediaText.isBlank()) ? dbpediaText : wikidataEnrichment.description();

        List<String> symptoms = wikidataEnrichment.symptoms();
        if ((symptoms == null || symptoms.isEmpty()) && dbpediaUri != null) {
            symptoms = dbpedia.symptoms(dbpediaUri);
        }

        List<String> riskFactors = wikidataEnrichment.riskFactors();
        if ((riskFactors == null || riskFactors.isEmpty()) && dbpediaUri != null) {
            riskFactors = dbpedia.riskFactorsOrRisks(dbpediaUri);
        }

        String image = wikidataEnrichment.image();
        if ((image == null || image.isBlank()) && dbpediaUri != null) {
            image = dbpedia.thumbnailUrl(dbpediaUri);
        }

        String snippet = wikidoc.loadSnippet(conditionId);

        return new ConditionDtos.ConditionDetail(
                SCHEMA_ORG_CONTEXT,
                MEAD_CONDITION_BASE_URL + conditionId,
                "MedicalCondition",
                localCondition.identifier(),
                localCondition.name(),
                description,
                image,
                symptoms == null ? List.of() : symptoms,
                riskFactors == null ? List.of() : riskFactors,
                localCondition.sameAs(),
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
