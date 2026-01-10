package com.mead.geography.controller;

import com.mead.geography.dto.GeographyDto.ObservationDetail;
import com.mead.geography.service.GeographyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class ObservationsController {

    private final GeographyService service;

    public ObservationsController(GeographyService service) {
        this.service = service;
    }

    @GetMapping("/observations")
    public List<ObservationDetail> list(
            @RequestParam String region,
            @RequestParam String indicator,
            @RequestParam(required = false) Integer fromYear,
            @RequestParam(required = false) Integer toYear) {
        return service.getObservations(region, indicator, fromYear, toYear);
    }
}
