package com.mead.conditions.enrich;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ImageNormalizationTest {

    @Test
    void testNormalizeSingle_HttpsEnforced() {
        assertThat(ImageNormalizer.normalizeSingle("http://example.com/img.jpg"))
                .isEqualTo("https://example.com/img.jpg");
    }

    @Test
    void testNormalizeSingle_QueryParamsStripped() {
        assertThat(ImageNormalizer.normalizeSingle("https://example.com/img.jpg?width=300"))
                .isEqualTo("https://example.com/img.jpg");
    }

    @Test
    void testNormalizeSingle_CommonsFilePath() {
        assertThat(ImageNormalizer.normalizeSingle("http://commons.wikimedia.org/wiki/Special:FilePath/Asthma.jpg"))
                .isEqualTo("https://commons.wikimedia.org/wiki/Special:FilePath/Asthma.jpg");
    }

    @Test
    void testNormalizeSingle_CommonsWikiFile() {
        assertThat(ImageNormalizer.normalizeSingle("https://commons.wikimedia.org/wiki/File:Asthma_attack.png"))
                .isEqualTo("https://commons.wikimedia.org/wiki/Special:FilePath/Asthma_attack.png");
    }

    @Test
    void testNormalize_DuplicatesRemoved() {
        List<String> raw = List.of(
                "http://example.com/img.jpg",
                "https://example.com/img.jpg",
                "https://EXAMPLE.com/img.jpg?q=1"
        );
        List<String> normalized = ImageNormalizer.normalize(raw);
        assertThat(normalized).containsExactly("https://example.com/img.jpg");
    }
}
