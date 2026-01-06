package com.mead.conditions.controller;

import com.mead.conditions.dto.ConditionDto.ConditionDetail;
import com.mead.conditions.dto.ConditionDto.ConditionSummary;
import com.mead.conditions.service.ConditionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class ConditionsController {

    private final ConditionService service;

    public ConditionsController(ConditionService service) {
        this.service = service;
    }

    @GetMapping("/conditions")
    public List<ConditionSummary> list() {
        return service.list();
    }

    @GetMapping("/conditions/{id}")
    public ConditionDetail get(@PathVariable String id) {
        return service.get(id);
    }
}
