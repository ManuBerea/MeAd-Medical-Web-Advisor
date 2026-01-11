package com.mead.geography.api;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class GeographyIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void regionEnrichment_containsExternalData() throws Exception {
        mvc.perform(get("/api/v1/regions/romania"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description", not(isEmptyString())))
                .andExpect(jsonPath("$.type", not(isEmptyString())))
                .andExpect(jsonPath("$.populationTotal", not(isEmptyString())))
                .andExpect(jsonPath("$.culturalFactors", hasSize(greaterThan(0))))
                .andExpect(jsonPath("$.wikidocSnippet", not(isEmptyString())));
    }
}
