package com.mead.geography.controller;

import com.mead.geography.dto.GeographyDto.IndicatorDetail;
import com.mead.geography.dto.GeographyDto.IndicatorSummary;
import com.mead.geography.service.GeographyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class IndicatorsController {

    private final GeographyService service;

    public IndicatorsController(GeographyService service) {
        this.service = service;
    }

    @GetMapping("/indicators")
    public List<IndicatorSummary> list() {
        return service.listIndicators();
    }

    @GetMapping("/indicators/{id}")
    public IndicatorDetail get(@PathVariable String id) {
        return service.getIndicator(id);
    }
}
