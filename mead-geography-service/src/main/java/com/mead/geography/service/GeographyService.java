package com.mead.geography.service;

import com.mead.geography.dto.GeographyDto.IndicatorDetail;
import com.mead.geography.dto.GeographyDto.IndicatorSummary;
import com.mead.geography.dto.GeographyDto.ObservationDetail;
import com.mead.geography.dto.GeographyDto.RegionDetail;
import com.mead.geography.dto.GeographyDto.RegionSummary;
import com.mead.geography.repository.IndicatorsRepository;
import com.mead.geography.repository.ObservationsRepository;
import com.mead.geography.repository.RegionsRepository;
import com.mead.geography.repository.IndicatorsRepository.Indicator;
import com.mead.geography.repository.ObservationsRepository.Observation;
import com.mead.geography.repository.RegionsRepository.Region;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class GeographyService {

    private static final String SCHEMA_ORG_CONTEXT = "https://schema.org/";
    private static final String PLACE_TYPE = "Place";
    private static final String DATASET_TYPE = "Dataset";
    private static final String OBSERVATION_TYPE = "Observation";

    private static final String MEAD_REGION_BASE_URL = "https://mead.example/region/";
    private static final String MEAD_INDICATOR_BASE_URL = "https://mead.example/indicator/";

    private final RegionsRepository regionsRepository;
    private final IndicatorsRepository indicatorsRepository;
    private final ObservationsRepository observationsRepository;

    public GeographyService(RegionsRepository regionsRepository,
                            IndicatorsRepository indicatorsRepository,
                            ObservationsRepository observationsRepository) {
        this.regionsRepository = regionsRepository;
        this.indicatorsRepository = indicatorsRepository;
        this.observationsRepository = observationsRepository;
    }

    public List<RegionSummary> listRegions() {
        return regionsRepository.findAll().stream()
                .map(region -> new RegionSummary(region.identifier(), region.name()))
                .toList();
    }

    public RegionDetail getRegion(String regionId) {
        Region region = regionsRepository.findById(regionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown region: " + regionId));

        return new RegionDetail(
                SCHEMA_ORG_CONTEXT,
                MEAD_REGION_BASE_URL + region.identifier(),
                PLACE_TYPE,
                region.identifier(),
                region.name(),
                region.sameAs(),
                region.containedInPlace()
        );
    }

    public List<IndicatorSummary> listIndicators() {
        return indicatorsRepository.findAll().stream()
                .map(indicator -> new IndicatorSummary(indicator.identifier(), indicator.name()))
                .toList();
    }

    public IndicatorDetail getIndicator(String indicatorId) {
        Indicator indicator = indicatorsRepository.findById(indicatorId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown indicator: " + indicatorId));

        return new IndicatorDetail(
                SCHEMA_ORG_CONTEXT,
                MEAD_INDICATOR_BASE_URL + indicator.identifier(),
                DATASET_TYPE,
                indicator.identifier(),
                indicator.name(),
                indicator.variableMeasured(),
                indicator.measurementTechnique()
        );
    }

    public List<ObservationDetail> getObservations(String regionId,
                                                   String indicatorId,
                                                   Integer fromYear,
                                                   Integer toYear) {
        Region region = regionsRepository.findById(regionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown region: " + regionId));
        Indicator indicator = indicatorsRepository.findById(indicatorId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown indicator: " + indicatorId));

        String regionUri = MEAD_REGION_BASE_URL + region.identifier();
        String indicatorUri = MEAD_INDICATOR_BASE_URL + indicator.identifier();

        List<Observation> observations = observationsRepository.findByRegionAndIndicator(regionUri, indicatorUri);

        return observations.stream()
                .filter(obs -> withinYearRange(obs.observationDate(), fromYear, toYear))
                .map(obs -> new ObservationDetail(
                        SCHEMA_ORG_CONTEXT,
                        obs.id(),
                        OBSERVATION_TYPE,
                        regionUri,
                        indicatorUri,
                        obs.observationDate(),
                        obs.value(),
                        obs.unitText()
                ))
                .toList();
    }

    private static boolean withinYearRange(String dateValue, Integer fromYear, Integer toYear) {
        if (fromYear == null && toYear == null) return true;
        Integer year = extractYear(dateValue);
        if (year == null) return false;
        if (fromYear != null && year < fromYear) return false;
        return toYear == null || year <= toYear;
    }

    private static Integer extractYear(String dateValue) {
        if (dateValue == null) return null;
        String trimmed = dateValue.trim();
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (Character.isDigit(c)) {
                digits.append(c);
            } else {
                break;
            }
        }
        if (digits.length() == 0) return null;
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
