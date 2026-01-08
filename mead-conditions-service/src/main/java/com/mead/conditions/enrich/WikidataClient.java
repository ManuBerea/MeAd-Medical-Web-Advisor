package com.mead.conditions.enrich;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import static com.mead.conditions.config.AsyncConfig.MEAD_EXECUTOR;
import static java.net.URLEncoder.encode;

@Component
public class WikidataClient {

    private static final String SOURCE_TAG = "Wikidata";
    private static final String CACHE_WIKIDATA_ENRICHMENT = "wikidataEnrichment";

    private static final String WD = "http://www.wikidata.org/entity/";
    private static final String WDT = "http://www.wikidata.org/prop/direct/";
    private static final String SCHEMA = "http://schema.org/";
    private static final String WIKIBASE = "http://wikiba.se/ontology#";
    private static final String BD = "http://www.bigdata.com/rdf#";

    private static final String LANG_EN = "en";
    private static final int LIMIT_DESCRIPTION = 1;
    private static final int LIMIT_LIST = 30;

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
            String image
    ) {}

    @Cacheable(CACHE_WIKIDATA_ENRICHMENT)
    public WikidataEnrichment enrichFromEntityUri(String wikidataEntityUri) {
        String qid = qidFromEntityUri(wikidataEntityUri);

        CompletableFuture<String> descriptionFuture = future(() -> fetchDescription(qid));
        CompletableFuture<List<String>> symptomsFuture = future(() -> fetchSymptoms(qid));
        CompletableFuture<List<String>> riskFactorsFuture = future(() -> fetchRiskFactors(qid));
        CompletableFuture<String> imageFuture = future(() -> fetchImageUrl(qid));

        CompletableFuture.allOf(descriptionFuture, symptomsFuture, riskFactorsFuture, imageFuture).join();

        return new WikidataEnrichment(
                descriptionFuture.join(),
                symptomsFuture.join(),
                riskFactorsFuture.join(),
                imageFuture.join()
        );
    }

    private String fetchDescription(String qid) {
        String sparqlQuery = """
                PREFIX wd: <%s>
                PREFIX schema: <%s>
                SELECT ?desc WHERE {
                  wd:%s schema:description ?desc .
                  FILTER(LANG(?desc) = "%s")
                } LIMIT %d
                """.formatted(WD, SCHEMA, qid, LANG_EN, LIMIT_DESCRIPTION);

        return sparql.selectFirstString(request(sparqlQuery, "desc"));
    }

    private List<String> fetchSymptoms(String qid) {
        String sparqlQuery = """
                PREFIX wd: <%s>
                PREFIX wdt: <%s>
                PREFIX wikibase: <%s>
                PREFIX bd: <%s>

                SELECT DISTINCT ?symptomLabel WHERE {
                  wd:%s wdt:P780 ?symptom .
                  SERVICE wikibase:label { bd:serviceParam wikibase:language "%s". }
                } LIMIT %d
                """.formatted(WD, WDT, WIKIBASE, BD, qid, LANG_EN, LIMIT_LIST);

        return sparql.selectStrings(request(sparqlQuery, "symptomLabel"));
    }

    private List<String> fetchRiskFactors(String qid) {
        String sparqlQuery = """
                PREFIX wd: <%s>
                PREFIX wdt: <%s>
                PREFIX wikibase: <%s>
                PREFIX bd: <%s>

                SELECT DISTINCT ?rfLabel WHERE {
                  wd:%s wdt:P5642 ?rf .
                  SERVICE wikibase:label { bd:serviceParam wikibase:language "%s". }
                } LIMIT %d
                """.formatted(WD, WDT, WIKIBASE, BD, qid, LANG_EN, LIMIT_LIST);

        return sparql.selectStrings(request(sparqlQuery, "rfLabel"));
    }

    private String fetchImageUrl(String qid) {
        String sparqlQuery = """
                PREFIX wd: <%s>
                PREFIX wdt: <%s>
                SELECT ?img WHERE {
                  wd:%s wdt:P18 ?img .
                } LIMIT 1
                """.formatted(WD, WDT, qid);

        String raw = sparql.selectFirstString(request(sparqlQuery, "img"));
        return commonsFileUrl(raw);
    }

    private SparqlHttpClient.SelectRequest request(String sparqlQuery, String varName) {
        return new SparqlHttpClient.SelectRequest(
                endpoint,
                timeoutMs,
                Map.of(SparqlHttpClient.HEADER_USER_AGENT, userAgent),
                sparqlQuery,
                varName,
                SOURCE_TAG
        );
    }

    private <T> CompletableFuture<T> future(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, meadExecutor);
    }

    public static String qidFromEntityUri(String wikidataEntityUri) {
        return wikidataEntityUri.substring(wikidataEntityUri.lastIndexOf('/') + 1);
    }

    private static final String COMMONS_FILEPATH = "https://commons.wikimedia.org/wiki/Special:FilePath/";
    private static final String COMMONS_HTTPS_URL = "https://commons.wikimedia.org/";
    private static final String COMMONS_HTTP_URL = "http://commons.wikimedia.org/";

    private static String commonsFileUrl(String input) {
        if (input == null || input.isBlank()) return null;

        int idx = input.lastIndexOf("Special:FilePath/");
        if (idx >= 0) {
            String tail = input.substring(idx + "Special:FilePath/".length());
            return COMMONS_FILEPATH + tail;
        }

        if (input.startsWith(COMMONS_HTTP_URL)) {
            return COMMONS_HTTPS_URL + input.substring(COMMONS_HTTP_URL.length());
        }
        if (input.startsWith(COMMONS_HTTPS_URL)) {
            return input;
        }

        String filename = input.startsWith("File:") ? input.substring("File:".length()) : input;
        filename = filename.replace(" ", "_");
        return COMMONS_FILEPATH + encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
