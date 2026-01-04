package com.mead.conditions.dto;

import java.util.List;

public final class ConditionDtos {

    public record ConditionSummary(
            String id,
            String name,
            List<String> sameAs
    ) {}

    public record ConditionDetail(
            String context,   // "@context"
            String id,        // "@id"
            String type,      // "@type"

            String identifier,
            String name,
            String description,

            // ONE image URL that works in a browser and in <img src="...">
            String image,

            List<String> symptoms,
            List<String> riskFactors,

            List<String> sameAs,
            String wikidocSnippet
    ) {}

    private ConditionDtos() {}
}