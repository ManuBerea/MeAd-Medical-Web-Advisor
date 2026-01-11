package com.mead.conditions.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.isEmptyString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ConditionsIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void obesity_enrichment_containsWikidocAndRiskFactors() throws Exception {
        mvc.perform(get("/api/v1/conditions/obesity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wikidocSnippet", not(isEmptyString())))
                .andExpect(jsonPath("$.riskFactors", hasSize(greaterThan(0))));
    }
}
