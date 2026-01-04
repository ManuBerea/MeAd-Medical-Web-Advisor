package com.mead.conditions.api;

import com.mead.conditions.dto.ConditionDtos;
import com.mead.conditions.data.LocalConditionsRepository;
import com.mead.conditions.enrich.DbpediaClient;
import com.mead.conditions.enrich.WikidataClient;
import com.mead.conditions.enrich.WikidocSnippetLoader;
import com.mead.conditions.service.ConditionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConditionServiceTest {

    private LocalConditionsRepository repo;
    private WikidataClient wikidata;
    private DbpediaClient dbpedia;
    private WikidocSnippetLoader wikidoc;
    private ConditionService service;

    @BeforeEach
    void setUp() {
        repo = mock(LocalConditionsRepository.class);
        wikidata = mock(WikidataClient.class);
        dbpedia = mock(DbpediaClient.class);
        wikidoc = mock(WikidocSnippetLoader.class);

        service = new ConditionService(repo, wikidata, dbpedia, wikidoc);
    }

    @Test
    void prefersDbpediaDescription_overWikidataDescription() {
        var local = new LocalConditionsRepository.LocalCondition(
                "asthma", "Asthma",
                List.of("http://dbpedia.org/resource/Asthma", "https://www.wikidata.org/entity/Q35869")
        );

        when(repo.findById("asthma")).thenReturn(Optional.of(local));

        when(wikidata.enrichFromEntityUri("https://www.wikidata.org/entity/Q35869"))
                .thenReturn(new WikidataClient.WikidataEnrichment(
                        "wd desc", List.of("wheeze"), List.of("smoking"),
                        "https://commons.wikimedia.org/wiki/Special:FilePath/Asthma.jpg"
                ));

        when(dbpedia.englishDescription("http://dbpedia.org/resource/Asthma"))
                .thenReturn("dbpedia desc");

        when(wikidoc.loadSnippet("asthma")).thenReturn("snippet");

        ConditionDtos.ConditionDetail detail = service.get("asthma");

        assertThat(detail.description()).isEqualTo("dbpedia desc");
    }

    @Test
    void fallsBackToWikidataDescription_whenDbpediaNull() {
        var local = new LocalConditionsRepository.LocalCondition(
                "asthma", "Asthma",
                List.of("http://dbpedia.org/resource/Asthma", "https://www.wikidata.org/entity/Q35869")
        );

        when(repo.findById("asthma")).thenReturn(Optional.of(local));

        when(wikidata.enrichFromEntityUri("https://www.wikidata.org/entity/Q35869"))
                .thenReturn(new WikidataClient.WikidataEnrichment(
                        "wd desc", List.of(), List.of(), null
                ));

        when(dbpedia.englishDescription("http://dbpedia.org/resource/Asthma"))
                .thenReturn(null);

        when(wikidoc.loadSnippet("asthma")).thenReturn("snippet");

        ConditionDtos.ConditionDetail detail = service.get("asthma");

        assertThat(detail.description()).isEqualTo("wd desc");
    }

    @Test
    void symptomsPreferWikidata_fallbackToDbpediaWhenWikidataEmpty() {
        var local = new LocalConditionsRepository.LocalCondition(
                "obesity", "Obesity",
                List.of("http://dbpedia.org/resource/Obesity", "https://www.wikidata.org/entity/Q12174")
        );
        when(repo.findById("obesity")).thenReturn(Optional.of(local));

        when(wikidata.enrichFromEntityUri("https://www.wikidata.org/entity/Q12174"))
                .thenReturn(new WikidataClient.WikidataEnrichment(
                        "wd", List.of(), List.of(), null
                ));

        when(dbpedia.symptoms("http://dbpedia.org/resource/Obesity"))
                .thenReturn(List.of("Increased fat"));

        when(dbpedia.riskFactorsOrRisks("http://dbpedia.org/resource/Obesity"))
                .thenReturn(List.of("High-calorie diet"));

        when(dbpedia.englishDescription("http://dbpedia.org/resource/Obesity"))
                .thenReturn("db");

        when(dbpedia.thumbnailUrl("http://dbpedia.org/resource/Obesity"))
                .thenReturn("https://commons.wikimedia.org/wiki/Special:FilePath/Obesity.svg");

        when(wikidoc.loadSnippet("obesity")).thenReturn("snippet");

        var detail = service.get("obesity");

        assertThat(detail.symptoms()).containsExactly("Increased fat");
        assertThat(detail.riskFactors()).containsExactly("High-calorie diet");
    }

    @Test
    void imagePreferWikidata_fallbackToDbpediaThumbnailWhenNull() {
        var local = new LocalConditionsRepository.LocalCondition(
                "obesity", "Obesity",
                List.of("http://dbpedia.org/resource/Obesity", "https://www.wikidata.org/entity/Q12174")
        );
        when(repo.findById("obesity")).thenReturn(Optional.of(local));

        when(wikidata.enrichFromEntityUri("https://www.wikidata.org/entity/Q12174"))
                .thenReturn(new WikidataClient.WikidataEnrichment(
                        "wd", List.of(), List.of(), null
                ));

        when(dbpedia.englishDescription("http://dbpedia.org/resource/Obesity")).thenReturn("db");
        when(dbpedia.thumbnailUrl("http://dbpedia.org/resource/Obesity"))
                .thenReturn("https://commons.wikimedia.org/wiki/Special:FilePath/Obesity.svg");

        when(dbpedia.symptoms("http://dbpedia.org/resource/Obesity")).thenReturn(List.of());
        when(dbpedia.riskFactorsOrRisks("http://dbpedia.org/resource/Obesity")).thenReturn(List.of());
        when(wikidoc.loadSnippet("obesity")).thenReturn("snippet");

        var detail = service.get("obesity");

        assertThat(detail.image()).isEqualTo("https://commons.wikimedia.org/wiki/Special:FilePath/Obesity.svg");
    }

    @Test
    void unknownCondition_throws() {
        when(repo.findById("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get("nope"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
