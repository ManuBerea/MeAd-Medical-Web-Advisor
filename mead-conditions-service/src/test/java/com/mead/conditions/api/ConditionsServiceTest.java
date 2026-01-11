package com.mead.conditions.api;

import com.mead.conditions.dto.ConditionDto.ConditionDetail;
import com.mead.conditions.dto.ConditionDto.ConditionSummary;
import com.mead.conditions.enrich.DbpediaClient.DbpediaEnrichment;
import com.mead.conditions.repository.ConditionsRepository;
import com.mead.conditions.enrich.DbpediaClient;
import com.mead.conditions.enrich.WikidataClient;
import com.mead.conditions.enrich.WikidocSnippetLoader;
import com.mead.conditions.repository.ConditionsRepository.Condition;
import com.mead.conditions.service.ConditionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConditionServiceTest {

    private ConditionsRepository repo;
    private WikidataClient wikidata;
    private DbpediaClient dbpedia;
    private WikidocSnippetLoader wikidoc;
    private ConditionService service;

    @BeforeEach
    void setUp() {
        repo = mock(ConditionsRepository.class);
        wikidata = mock(WikidataClient.class);
        dbpedia = mock(DbpediaClient.class);
        wikidoc = mock(WikidocSnippetLoader.class);
        service = new ConditionService(repo, wikidata, dbpedia, wikidoc);
    }

    @Test
    void prefersDbpediaDescription_overWikidataDescription() {
        Condition condition = new Condition(
                "asthma", "Asthma",
                List.of("http://dbpedia.org/resource/Asthma", "https://www.wikidata.org/entity/Q35869")
        );
        when(repo.findById("asthma")).thenReturn(Optional.of(condition));

        when(wikidata.enrichFromEntityUri("https://www.wikidata.org/entity/Q35869"))
                .thenReturn(new WikidataClient.WikidataEnrichment(
                        "wd desc", List.of("wheeze"), List.of("smoking"),
                        List.of("https://commons.wikimedia.org/wiki/Special:FilePath/Asthma.jpg")
                ));

        when(dbpedia.enrichFromResourceUri("http://dbpedia.org/resource/Asthma"))
                .thenReturn(new DbpediaEnrichment(
                        "dbpedia desc", List.of(), List.of(), List.of()
                ));

        when(wikidoc.fetchOverview("asthma", "Asthma")).thenReturn("overview");
        when(wikidoc.fetchCauses("asthma", "Asthma")).thenReturn(List.of());
        when(wikidoc.fetchRiskFactors("asthma", "Asthma")).thenReturn(List.of());

        ConditionDetail detail = service.get("asthma");

        assertThat(detail.description()).isEqualTo("dbpedia desc");
    }

    @Test
    void fallsBackToWikidataDescription_whenDbpediaBlank() {
        Condition condition = new Condition(
                "asthma", "Asthma",
                List.of("http://dbpedia.org/resource/Asthma", "https://www.wikidata.org/entity/Q35869")
        );
        when(repo.findById("asthma")).thenReturn(Optional.of(condition));

        when(wikidata.enrichFromEntityUri("https://www.wikidata.org/entity/Q35869"))
                .thenReturn(new WikidataClient.WikidataEnrichment(
                        "wd desc", List.of(), List.of(), List.of()
                ));

        when(dbpedia.enrichFromResourceUri("http://dbpedia.org/resource/Asthma"))
                .thenReturn(new DbpediaEnrichment(
                        "  ", List.of(), List.of(), List.of()
                ));

        when(wikidoc.fetchOverview("asthma", "Asthma")).thenReturn("overview");
        when(wikidoc.fetchCauses("asthma", "Asthma")).thenReturn(List.of());
        when(wikidoc.fetchRiskFactors("asthma", "Asthma")).thenReturn(List.of());

        ConditionDetail detail = service.get("asthma");

        assertThat(detail.description()).isEqualTo("wd desc");
    }

    @Test
    void symptomsPreferWikidata_fallbackToDbpediaWhenWikidataEmpty() {
        Condition condition = new Condition(
                "obesity", "Obesity",
                List.of("http://dbpedia.org/resource/Obesity", "https://www.wikidata.org/entity/Q12174")
        );
        when(repo.findById("obesity")).thenReturn(Optional.of(condition));

        when(wikidata.enrichFromEntityUri("https://www.wikidata.org/entity/Q12174"))
                .thenReturn(new WikidataClient.WikidataEnrichment(
                        "wd", List.of(), List.of(), List.of()
                ));

        when(dbpedia.enrichFromResourceUri("http://dbpedia.org/resource/Obesity"))
                .thenReturn(new DbpediaEnrichment(
                        "db", List.of("Increased fat"), List.of("High-calorie diet"),
                        List.of("https://commons.wikimedia.org/wiki/Special:FilePath/Obesity.svg")
                ));

        when(wikidoc.fetchOverview("obesity", "Obesity")).thenReturn("overview");
        when(wikidoc.fetchCauses("obesity", "Obesity")).thenReturn(List.of());
        when(wikidoc.fetchRiskFactors("obesity", "Obesity")).thenReturn(List.of());
        when(wikidoc.fetchSymptoms("obesity", "Obesity")).thenReturn(List.of("Fatigue"));

        ConditionDetail detail = service.get("obesity");

        assertThat(detail.symptoms()).containsExactly("Increased fat");
        assertThat(detail.riskFactors()).containsExactly("High-calorie diet");
    }

    @Test
    void fallsBackToWikidocSymptoms_whenKnowledgeBasesEmpty() {
        Condition condition = new Condition(
                "gingivitis", "Gingivitis",
                List.of("http://dbpedia.org/resource/Gingivitis", "https://www.wikidata.org/entity/Q9235")
        );
        when(repo.findById("gingivitis")).thenReturn(Optional.of(condition));

        when(wikidata.enrichFromEntityUri("https://www.wikidata.org/entity/Q9235"))
                .thenReturn(new WikidataClient.WikidataEnrichment(
                        "wd", List.of(), List.of(), List.of()
                ));

        when(dbpedia.enrichFromResourceUri("http://dbpedia.org/resource/Gingivitis"))
                .thenReturn(new DbpediaEnrichment(
                        "db", List.of(), List.of(), List.of()
                ));

        when(wikidoc.fetchOverview("gingivitis", "Gingivitis")).thenReturn("overview");
        when(wikidoc.fetchCauses("gingivitis", "Gingivitis")).thenReturn(List.of());
        when(wikidoc.fetchRiskFactors("gingivitis", "Gingivitis")).thenReturn(List.of());
        when(wikidoc.fetchSymptoms("gingivitis", "Gingivitis")).thenReturn(List.of("Gum bleeding"));

        ConditionDetail detail = service.get("gingivitis");

        assertThat(detail.symptoms()).containsExactly("Gum bleeding");
    }

    @Test
    void imagePreferWikidata_fallbackToDbpediaThumbnailWhenNull() {
        Condition condition = new Condition(
                "obesity", "Obesity",
                List.of("http://dbpedia.org/resource/Obesity", "https://www.wikidata.org/entity/Q12174")
        );
        when(repo.findById("obesity")).thenReturn(Optional.of(condition));

        when(wikidata.enrichFromEntityUri("https://www.wikidata.org/entity/Q12174"))
                .thenReturn(new WikidataClient.WikidataEnrichment(
                        "wd", List.of(), List.of(), List.of()
                ));

        when(dbpedia.enrichFromResourceUri("http://dbpedia.org/resource/Obesity"))
                .thenReturn(new DbpediaEnrichment(
                        "db", List.of(), List.of(),
                        List.of("https://commons.wikimedia.org/wiki/Special:FilePath/Obesity.svg")
                ));

        when(wikidoc.fetchOverview("obesity", "Obesity")).thenReturn("overview");
        when(wikidoc.fetchCauses("obesity", "Obesity")).thenReturn(List.of());
        when(wikidoc.fetchRiskFactors("obesity", "Obesity")).thenReturn(List.of());
        when(wikidoc.fetchSymptoms("obesity", "Obesity")).thenReturn(List.of());

        ConditionDetail detail = service.get("obesity");

        assertThat(detail.images()).containsExactly("https://commons.wikimedia.org/wiki/Special:FilePath/Obesity.svg");
    }

    @Test
    void missingWikidataUri_doesNotCallWikidata_andUsesDbpedia() {
        Condition condition = new Condition(
                "x", "X",
                List.of("http://dbpedia.org/resource/Obesity")
        );

        when(repo.findById("x")).thenReturn(Optional.of(condition));
        when(dbpedia.enrichFromResourceUri("http://dbpedia.org/resource/Obesity"))
                .thenReturn(new DbpediaEnrichment(
                        "db desc", List.of("s1"), List.of("r1"), List.of("img")
                ));
        when(wikidoc.fetchOverview("x", "X")).thenReturn("overview");
        when(wikidoc.fetchCauses("x", "X")).thenReturn(List.of());
        when(wikidoc.fetchRiskFactors("x", "X")).thenReturn(List.of());
        when(wikidoc.fetchSymptoms("x", "X")).thenReturn(List.of());

        ConditionDetail detail = service.get("x");

        verifyNoInteractions(wikidata);
        assertThat(detail.description()).isEqualTo("db desc");
        assertThat(detail.symptoms()).containsExactly("s1");
        assertThat(detail.riskFactors()).containsExactly("r1");
        assertThat(detail.images()).containsExactly("img");
    }

    @Test
    void missingDbpediaUri_doesNotCallDbpedia_andUsesWikidata() {
        Condition condition = new Condition(
                "x", "X",
                List.of("https://www.wikidata.org/entity/Q12174")
        );

        when(repo.findById("x")).thenReturn(Optional.of(condition));
        when(wikidata.enrichFromEntityUri("https://www.wikidata.org/entity/Q12174"))
                .thenReturn(new WikidataClient.WikidataEnrichment(
                        "wd desc", List.of("s1"), List.of("r1"), List.of("img")
                ));
        when(wikidoc.fetchOverview("x", "X")).thenReturn("overview");
        when(wikidoc.fetchCauses("x", "X")).thenReturn(List.of());
        when(wikidoc.fetchRiskFactors("x", "X")).thenReturn(List.of());
        when(wikidoc.fetchSymptoms("x", "X")).thenReturn(List.of());

        ConditionDetail detail = service.get("x");

        verifyNoInteractions(dbpedia);
        assertThat(detail.description()).isEqualTo("wd desc");
        assertThat(detail.symptoms()).containsExactly("s1");
        assertThat(detail.riskFactors()).containsExactly("r1");
        assertThat(detail.images()).containsExactly("img");
    }

    @Test
    void mergesWikidocCausesAndRiskFactors_withoutDuplicates() {
        Condition condition = new Condition(
                "obesity", "Obesity",
                List.of("http://dbpedia.org/resource/Obesity", "https://www.wikidata.org/entity/Q12174")
        );
        when(repo.findById("obesity")).thenReturn(Optional.of(condition));

        when(wikidata.enrichFromEntityUri("https://www.wikidata.org/entity/Q12174"))
                .thenReturn(new WikidataClient.WikidataEnrichment(
                        "wd", List.of(), List.of("Smoking"), List.of()
                ));

        when(dbpedia.enrichFromResourceUri("http://dbpedia.org/resource/Obesity"))
                .thenReturn(new DbpediaEnrichment(
                        "db", List.of(), List.of(), List.of()
                ));

        when(wikidoc.fetchOverview("obesity", "Obesity")).thenReturn("overview");
        when(wikidoc.fetchCauses("obesity", "Obesity")).thenReturn(List.of("High calorie intake", "Smoking"));
        when(wikidoc.fetchRiskFactors("obesity", "Obesity")).thenReturn(List.of("Sedentary lifestyle"));
        when(wikidoc.fetchSymptoms("obesity", "Obesity")).thenReturn(List.of());

        ConditionDetail detail = service.get("obesity");

        assertThat(detail.riskFactors())
                .containsExactly("Smoking", "High calorie intake", "Sedentary lifestyle");
    }

    @Test
    void list_returnsSummaries() {
        when(repo.findAll()).thenReturn(List.of(
                new Condition(
                        "asthma", "Asthma",
                        List.of("http://dbpedia.org/resource/Asthma", "https://www.wikidata.org/entity/Q35869")
                )
        ));

        List<ConditionSummary> conditionslist = service.list();

        assertThat(conditionslist).hasSize(1);
        assertThat(conditionslist.get(0).id()).isEqualTo("asthma");
        assertThat(conditionslist.get(0).name()).isEqualTo("Asthma");
    }

    @Test
    void unknownCondition_throws() {
        when(repo.findById("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get("nope"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
