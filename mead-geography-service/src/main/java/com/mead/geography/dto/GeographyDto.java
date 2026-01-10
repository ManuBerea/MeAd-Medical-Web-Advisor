package com.mead.geography.dto;

import java.util.List;

public final class GeographyDto {

    public record RegionSummary(
            String id,
            String name
    ) {}

    public record RegionDetail(
            String context,
            String id,
            String type,
            String identifier,
            String name,
            List<String> sameAs,
            List<String> containedInPlace
    ) {}

    public record IndicatorSummary(
            String id,
            String name
    ) {}

    public record IndicatorDetail(
            String context,
            String id,
            String type,
            String identifier,
            String name,
            String variableMeasured,
            String measurementTechnique
    ) {}

    public record ObservationDetail(
            String context,
            String id,
            String type,
            String observationAbout,
            String measuredProperty,
            String observationDate,
            Double value,
            String unitText
    ) {}

    private GeographyDto() {}
}
