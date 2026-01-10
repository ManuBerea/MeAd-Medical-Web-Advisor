package com.mead.conditions.enrich;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Utility to normalize image URLs and remove duplicates.
 */
public class ImageNormalizer {

    private static final String COMMONS_PATH = "https://commons.wikimedia.org/wiki/Special:FilePath/";

    public static List<String> normalize(List<String> urls) {
        if (urls == null) return List.of();
        Map<String, String> map = new LinkedHashMap<>();
        for (String url : urls) {
            String norm = normalizeSingle(url);
            if (norm != null && !norm.isBlank()) {
                map.putIfAbsent(norm.toLowerCase(), norm);
            }
        }
        return new ArrayList<>(map.values());
    }

    public static String normalizeSingle(String url) {
        if (url == null || url.isBlank()) return null;

        // 1. Strip query params and enforce HTTPS
        String res = url.trim().split("\\?")[0].replace("http://", "https://");

        // 2. Normalize Wikimedia Commons URLs
        if (res.contains("commons.wikimedia.org")) {
            String filename = null;
            if (res.contains("Special:FilePath/")) {
                filename = res.substring(res.lastIndexOf('/') + 1);
            } else if (res.contains("/wiki/File:")) {
                filename = res.substring(res.lastIndexOf(':') + 1);
            }

            if (filename != null) {
                String clean = URLDecoder.decode(filename, StandardCharsets.UTF_8).replace(" ", "_");
                return COMMONS_PATH + URLEncoder.encode(clean, StandardCharsets.UTF_8).replace("+", "_");
            }
        }

        return res;
    }
}
