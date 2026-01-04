package com.mead.conditions.api;

import com.mead.conditions.controller.ConditionsController;
import com.mead.conditions.dto.ConditionDtos;
import com.mead.conditions.service.ConditionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = ConditionsController.class)
class ConditionsControllerWebTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    ConditionService conditionService;

    @Test
    void getCondition_returns200_andJson() throws Exception {
        var detail = new ConditionDtos.ConditionDetail(
                "https://schema.org/",
                "https://mead.example/condition/asthma",
                "MedicalCondition",
                "asthma",
                "Asthma",
                "Some description",
                "https://commons.wikimedia.org/wiki/Special:FilePath/Asthma.jpg",
                List.of("wheeze"),
                List.of("smoking"),
                List.of("http://dbpedia.org/resource/Asthma", "https://www.wikidata.org/entity/Q35869"),
                "snippet"
        );

        when(conditionService.get("asthma")).thenReturn(detail);

        mvc.perform(get("/api/v1/conditions/asthma"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.identifier").value("asthma"))
                .andExpect(jsonPath("$.image").exists());
    }

    @Test
    void getUnknownCondition_returns404() throws Exception {
        when(conditionService.get("nope"))
                .thenThrow(new IllegalArgumentException("Unknown condition: nope"));

        mvc.perform(get("/api/v1/conditions/nope"))
                .andExpect(status().isNotFound());
    }

    @Test
    void listConditions_returns200() throws Exception {
        when(conditionService.list()).thenReturn(List.of(
                new ConditionDtos.ConditionSummary("asthma", "Asthma",
                        List.of("http://dbpedia.org/resource/Asthma", "https://www.wikidata.org/entity/Q35869"))
        ));

        mvc.perform(get("/api/v1/conditions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("asthma"));
    }
}
