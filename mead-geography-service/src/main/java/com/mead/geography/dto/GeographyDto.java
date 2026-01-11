package com.mead.geography.dto;

import java.util.List;

public final class GeographyDto {

    public record RegionSummary(
            String id,
            String name,
            String type,
            List<String> sameAs
    ) {}

    public record RegionDetail(
            String context,
            String id,
            String type,
            String identifier,
            String name,
            String description,
            String populationTotal,
            String populationDensity,
            List<String> culturalFactors,
            List<String> images,
            List<String> sameAs,
            String wikipediaSnippet
    ) {}

    private GeographyDto() {}
}
