package com.mead.conditions.service;

import com.mead.conditions.dto.ConditionDtos;
import com.mead.conditions.data.LocalConditionsRepository;
import com.mead.conditions.enrich.DbpediaClient;
import com.mead.conditions.enrich.WikidataClient;
import com.mead.conditions.enrich.WikidocSnippetLoader;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ConditionService {

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
        var local = repo.findById(conditionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown condition: " + conditionId));

        String wikidataUri = pickSameAs(local.sameAs(), "wikidata.org/entity/");
        String dbpediaUri = pickSameAs(local.sameAs(), "dbpedia.org/resource/");

        var wd = (wikidataUri != null)
                ? wikidata.enrichFromEntityUri(wikidataUri)
                : new WikidataClient.WikidataEnrichment(null, List.of(), List.of(), null);

        String dbpediaText = (dbpediaUri != null) ? dbpedia.englishDescription(dbpediaUri) : null;
        String description = (dbpediaText != null && !dbpediaText.isBlank()) ? dbpediaText : wd.description();

        // Symptoms: prefer Wikidata; fallback to DBpedia infobox
        List<String> symptoms = wd.symptoms();
        if ((symptoms == null || symptoms.isEmpty()) && dbpediaUri != null) {
            symptoms = dbpedia.symptoms(dbpediaUri);
        }

        // Risk factors: prefer Wikidata; fallback to DBpedia causes/risks
        List<String> riskFactors = wd.riskFactors();
        if ((riskFactors == null || riskFactors.isEmpty()) && dbpediaUri != null) {
            riskFactors = dbpedia.riskFactorsOrRisks(dbpediaUri);
        }

        // Image: prefer Wikidata; fallback to DBpedia thumbnail
        String image = wd.image();
        if ((image == null || image.isBlank()) && dbpediaUri != null) {
            image = dbpedia.thumbnailUrl(dbpediaUri);
        }

        String snippet = wikidoc.loadSnippet(conditionId);

        return new ConditionDtos.ConditionDetail(
                "https://schema.org/",
                "https://mead.example/condition/" + conditionId,
                "MedicalCondition",
                local.identifier(),
                local.name(),
                description,
                image,
                symptoms == null ? List.of() : symptoms,
                riskFactors == null ? List.of() : riskFactors,
                local.sameAs(),
                snippet
        );
    }

    private static String pickSameAs(List<String> sameAsList, String contains) {
        return sameAsList.stream()
                .filter(u -> u != null && u.contains(contains))
                .findFirst()
                .orElse(null);
    }
}
