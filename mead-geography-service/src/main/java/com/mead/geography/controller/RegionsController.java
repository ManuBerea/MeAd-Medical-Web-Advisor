package com.mead.geography.controller;

import com.mead.geography.dto.GeographyDto.RegionDetail;
import com.mead.geography.dto.GeographyDto.RegionSummary;
import com.mead.geography.service.GeographyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class RegionsController {

    private final GeographyService service;

    public RegionsController(GeographyService service) {
        this.service = service;
    }

    @GetMapping("/regions")
    public List<RegionSummary> list() {
        return service.listRegions();
    }

    @GetMapping("/regions/{id}")
    public RegionDetail get(@PathVariable String id) {
        return service.getRegion(id);
    }
}
