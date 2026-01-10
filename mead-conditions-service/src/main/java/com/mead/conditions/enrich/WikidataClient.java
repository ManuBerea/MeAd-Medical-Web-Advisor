package com.mead.conditions.enrich;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import static com.mead.conditions.config.AsyncConfig.MEAD_EXECUTOR;

@Component
public class WikidataClient {

    private static final String WD = "http://www.wikidata.org/entity/";
    private static final String WDT = "http://www.wikidata.org/prop/direct/";
    private static final String SCHEMA = "http://schema.org/";
    private static final String WIKIBASE = "http://wikiba.se/ontology#";
    private static final String BD = "http://www.bigdata.com/rdf#";

    private static final String LANG_EN = "en";
    private static final int LIMIT_DESCRIPTION = 1;
    private static final int LIMIT_LIST = 30;
    private static final int LIMIT_IMAGES = 10;

    @Value("${mead.external.wikidata.endpoint}")
    private String endpoint;

    @Value("${mead.external.wikidata.user-agent}")
    private String userAgent;

    @Value("${mead.external.wikidata.timeout-ms:8000}")
    private long timeoutMs;

    private final SparqlHttpClient sparql;
    private final Executor meadExecutor;

    public WikidataClient(SparqlHttpClient sparql,
                          @Qualifier(MEAD_EXECUTOR) Executor meadExecutor) {
        this.sparql = sparql;
        this.meadExecutor = meadExecutor;
    }

    public record WikidataEnrichment(
            String description,
            List<String> symptoms,
            List<String> riskFactors,
            List<String> images
    ) {}

    @Cacheable("wikidataEnrichment")
    public WikidataEnrichment enrichFromEntityUri(String wikidataEntityUri) {
        String entityId = wikidataEntityUri.substring(wikidataEntityUri.lastIndexOf('/') + 1);

        CompletableFuture<String> descriptionFuture = executeAsync(() -> fetchDescription(entityId));
        CompletableFuture<List<String>> symptomsFuture = executeAsync(() -> fetchSymptoms(entityId));
        CompletableFuture<List<String>> riskFactorsFuture = executeAsync(() -> fetchRiskFactors(entityId));
        CompletableFuture<List<String>> imagesFuture = executeAsync(() -> fetchImageUrls(entityId));

        CompletableFuture.allOf(descriptionFuture, symptomsFuture, riskFactorsFuture, imagesFuture).join();

        return new WikidataEnrichment(
                descriptionFuture.join(),
                symptomsFuture.join(),
                riskFactorsFuture.join(),
                imagesFuture.join()
        );
    }

    private String fetchDescription(String entityId) {
        String sparqlQuery = """
                PREFIX wd: <%s>
                PREFIX schema: <%s>
                SELECT ?desc WHERE {
                  wd:%s schema:description ?desc .
                  FILTER(LANG(?desc) = "%s")
                } LIMIT %d
                """.formatted(WD, SCHEMA, entityId, LANG_EN, LIMIT_DESCRIPTION);

        return sparql.selectFirstString(createRequest(sparqlQuery, "desc"));
    }

    private List<String> fetchSymptoms(String entityId) {
        String sparqlQuery = """
                PREFIX wd: <%s>
                PREFIX wdt: <%s>
                PREFIX wikibase: <%s>
                PREFIX bd: <%s>

                SELECT DISTINCT ?symptomLabel WHERE {
                  wd:%s wdt:P780 ?symptom .
                  SERVICE wikibase:label { bd:serviceParam wikibase:language "%s". }
                } LIMIT %d
                """.formatted(WD, WDT, WIKIBASE, BD, entityId, LANG_EN, LIMIT_LIST);

        return sparql.selectStrings(createRequest(sparqlQuery, "symptomLabel"));
    }

    private List<String> fetchRiskFactors(String entityId) {
        String sparqlQuery = """
                PREFIX wd: <%s>
                PREFIX wdt: <%s>
                PREFIX wikibase: <%s>
                PREFIX bd: <%s>

                SELECT DISTINCT ?rfLabel WHERE {
                  wd:%s wdt:P5642 ?rf .
                  SERVICE wikibase:label { bd:serviceParam wikibase:language "%s". }
                } LIMIT %d
                """.formatted(WD, WDT, WIKIBASE, BD, entityId, LANG_EN, LIMIT_LIST);

        return sparql.selectStrings(createRequest(sparqlQuery, "rfLabel"));
    }

    private List<String> fetchImageUrls(String entityId) {
        String sparqlQuery = """
                PREFIX wd: <%s>
                PREFIX wdt: <%s>
                SELECT ?img WHERE {
                  wd:%s wdt:P18 ?img .
                } LIMIT %d
                """.formatted(WD, WDT, entityId, LIMIT_IMAGES);

        return sparql.selectStrings(createRequest(sparqlQuery, "img"));
    }

    private SparqlHttpClient.SelectRequest createRequest(String sparqlQuery, String varName) {
        return new SparqlHttpClient.SelectRequest(
                endpoint,
                timeoutMs,
                Map.of(SparqlHttpClient.HEADER_USER_AGENT, userAgent),
                sparqlQuery,
                varName,
                "Wikidata"
        );
    }

    private <T> CompletableFuture<T> executeAsync(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, meadExecutor);
    }
}
