package com.mead.geography.api;

import com.mead.geography.controller.RegionsController;
import com.mead.geography.dto.GeographyDto;
import com.mead.geography.service.GeographyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = RegionsController.class)
class RegionsControllerWebTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    GeographyService geographyService;

    @Test
    void getRegion_returns200_andJson() throws Exception {
        var detail = new GeographyDto.RegionDetail(
                "https://schema.org/",
                "https://mead.example/region/romania",
                "Place",
                "romania",
                "Romania",
                "Some description",
                "19000000",
                "84.0",
                List.of("temperate"),
                List.of("manufacturing"),
                List.of("Romanian"),
                List.of("https://commons.wikimedia.org/wiki/Special:FilePath/Romania.jpg"),
                List.of("http://dbpedia.org/resource/Romania", "https://www.wikidata.org/entity/Q218"),
                "snippet"
        );

        when(geographyService.getRegion("romania")).thenReturn(detail);

        mvc.perform(get("/api/v1/regions/romania"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.identifier").value("romania"))
                .andExpect(jsonPath("$.populationTotal").value("19000000"));
    }

    @Test
    void getUnknownRegion_returns404() throws Exception {
        when(geographyService.getRegion("nope"))
                .thenThrow(new IllegalArgumentException("Unknown region: nope"));

        mvc.perform(get("/api/v1/regions/nope"))
                .andExpect(status().isNotFound());
    }

    @Test
    void listRegions_returns200() throws Exception {
        when(geographyService.listRegions()).thenReturn(List.of(
                new GeographyDto.RegionSummary(
                        "romania",
                        "Romania",
                        List.of("http://dbpedia.org/resource/Romania", "https://www.wikidata.org/entity/Q218")
                )
        ));

        mvc.perform(get("/api/v1/regions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("romania"));
    }
}
