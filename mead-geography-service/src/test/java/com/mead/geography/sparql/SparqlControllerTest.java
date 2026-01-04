package com.mead.geography.sparql;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class SparqlControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void sparqlSelectReturnsJson() throws Exception {
        String q = """
                PREFIX schema: <https://schema.org/>
                SELECT ?s ?name WHERE {
                  ?s schema:name ?name .
                } LIMIT 5
                """;

        mvc.perform(post("/api/v1/sparql")
                        .contentType("application/sparql-query")
                        .content(q))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/sparql-results+json"));
    }
}
