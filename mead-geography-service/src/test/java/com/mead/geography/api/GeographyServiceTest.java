package com.mead.geography.api;

import com.mead.geography.dto.GeographyDto.RegionDetail;
import com.mead.geography.dto.GeographyDto.RegionSummary;
import com.mead.geography.enrich.DbpediaClient;
import com.mead.geography.enrich.DbpediaClient.DbpediaEnrichment;
import com.mead.geography.enrich.WikidataClient;
import com.mead.geography.enrich.WikipediaSummaryLoader;
import com.mead.geography.repository.RegionsRepository;
import com.mead.geography.repository.RegionsRepository.Region;
import com.mead.geography.service.GeographyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class GeographyServiceTest {

    private RegionsRepository repo;
    private WikidataClient wikidata;
    private DbpediaClient dbpedia;
    private WikipediaSummaryLoader wikipedia;
    private GeographyService service;

    @BeforeEach
    void setUp() {
        repo = mock(RegionsRepository.class);
        wikidata = mock(WikidataClient.class);
        dbpedia = mock(DbpediaClient.class);
        wikipedia = mock(WikipediaSummaryLoader.class);
        service = new GeographyService(repo, wikidata, dbpedia, wikipedia);
    }

    @Test
    void prefersDbpediaDescription_overWikidataDescription() {
        Region region = new Region(
                "germany",
                "Germany",
                List.of("http://dbpedia.org/resource/Germany", "https://www.wikidata.org/entity/Q183")
        );
        when(repo.findById("germany")).thenReturn(Optional.of(region));

        when(wikidata.enrichFromEntityUri("https://www.wikidata.org/entity/Q183"))
                .thenReturn(new WikidataClient.WikidataEnrichment(
                        "wd desc", "100", "5.0",
                        List.of(), List.of()
                ));

        when(dbpedia.enrichFromResourceUri("http://dbpedia.org/resource/Germany"))
                .thenReturn(new DbpediaEnrichment(
                        "db desc", "90", "6.0",
                        List.of(), List.of()
                ));

        when(wikipedia.loadSummary("germany", "Germany")).thenReturn("snippet");

        RegionDetail detail = service.getRegion("germany");

        assertThat(detail.description()).isEqualTo("db desc");
    }

    @Test
    void populationTotalPrefersWikidata_densityPrefersDbpedia() {
        Region region = new Region(
                "france",
                "France",
                List.of("http://dbpedia.org/resource/France", "https://www.wikidata.org/entity/Q142")
        );
        when(repo.findById("france")).thenReturn(Optional.of(region));

        when(wikidata.enrichFromEntityUri("https://www.wikidata.org/entity/Q142"))
                .thenReturn(new WikidataClient.WikidataEnrichment(
                        "wd desc", "1000", "7.5",
                        List.of("French"), List.of("img1")
                ));

        when(dbpedia.enrichFromResourceUri("http://dbpedia.org/resource/France"))
                .thenReturn(new DbpediaEnrichment(
                        "db desc", "900", "9.9",
                        List.of("French"), List.of("img2")
                ));

        when(wikipedia.loadSummary("france", "France")).thenReturn("snippet");

        RegionDetail detail = service.getRegion("france");

        assertThat(detail.populationTotal()).isEqualTo("1000");
        assertThat(detail.populationDensity()).isEqualTo("9.9");
    }

    @Test
    void missingWikidataUri_doesNotCallWikidata_andUsesDbpedia() {
        Region region = new Region(
                "berlin",
                "Berlin",
                List.of("http://dbpedia.org/resource/Berlin")
        );

        when(repo.findById("berlin")).thenReturn(Optional.of(region));
        when(dbpedia.enrichFromResourceUri("http://dbpedia.org/resource/Berlin"))
                .thenReturn(new DbpediaEnrichment(
                        "db desc", "3500000", "4000",
                        List.of("German"), List.of("img")
                ));
        when(wikipedia.loadSummary("berlin", "Berlin")).thenReturn("snippet");

        RegionDetail detail = service.getRegion("berlin");

        verifyNoInteractions(wikidata);
        assertThat(detail.description()).isEqualTo("db desc");
        assertThat(detail.populationTotal()).isEqualTo("3500000");
        assertThat(detail.populationDensity()).isEqualTo("4000");
    }

    @Test
    void list_returnsSummaries() {
        when(repo.findAll()).thenReturn(List.of(
                new Region(
                        "europe",
                        "Europe",
                        List.of("http://dbpedia.org/resource/Europe", "https://www.wikidata.org/entity/Q46")
                )
        ));
        when(wikidata.fetchRegionType("https://www.wikidata.org/entity/Q46"))
                .thenReturn("Continent");
        when(wikidata.enrichFromEntityUri("https://www.wikidata.org/entity/Q46"))
                .thenReturn(new WikidataClient.WikidataEnrichment(
                        "desc", "100", "5.0", List.of(), List.of()
                ));
        when(dbpedia.enrichFromResourceUri("http://dbpedia.org/resource/Europe"))
                .thenReturn(new DbpediaEnrichment(
                        "desc", "100", "5.0", List.of(), List.of()
                ));

        List<RegionSummary> regionsList = service.listRegions();

        assertThat(regionsList).hasSize(1);
        assertThat(regionsList.get(0).id()).isEqualTo("europe");
        assertThat(regionsList.get(0).type()).isEqualTo("Continent");
        assertThat(regionsList.get(0).sameAs()).hasSize(2);
    }

    @Test
    void unknownRegion_throws() {
        when(repo.findById("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getRegion("nope"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
