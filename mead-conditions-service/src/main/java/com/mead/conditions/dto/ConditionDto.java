package com.mead.conditions.dto;

import java.util.List;

public final class ConditionDto {

    public record ConditionSummary(
            String id,
            String name,
            List<String> sameAs
    ) {}

    public record ConditionDetail(
            String context,
            String id,
            String type,

            String identifier,
            String name,
            String description,

            String image,
            List<String> images,

            List<String> symptoms,
            List<String> riskFactors,

            List<String> sameAs,
            String wikidocSnippet
    ) {}

    private ConditionDto() {}
}
