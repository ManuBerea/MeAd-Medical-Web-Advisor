package com.mead.conditions.controller;

import com.mead.conditions.dto.ConditionDtos;
import com.mead.conditions.service.ConditionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class ConditionsController {

    private final ConditionService service;

    public ConditionsController(ConditionService service) {
        this.service = service;
    }

    @GetMapping("/conditions")
    public List<ConditionDtos.ConditionSummary> list() {
        return service.list();
    }

    @GetMapping("/conditions/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        try {
            return ResponseEntity.ok(service.get(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(e.getMessage());
        }
    }
}
