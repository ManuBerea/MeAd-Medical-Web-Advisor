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

            List<String> images,

            List<String> symptoms,
            List<String> riskFactors,

            List<String> sameAs,
            
            // WikiDoc enrichment - multiple sections from medical encyclopedia
            WikidocInfo wikidoc
    ) {}
    
    /**
     * WikiDoc information containing multiple sections from the medical encyclopedia.
     */
    public record WikidocInfo(
            String overview,           // Main overview/introduction
            String causes,             // Causes / Etiology  
            String pathophysiology,    // How the disease affects the body
            String diagnosis,          // Diagnostic methods
            String treatment,          // Treatment options
            String prevention,         // Prevention measures
            String prognosis,          // Expected outcomes
            String epidemiology,       // Population statistics
            String sourceUrl,          // Direct URL to the WikiDoc article
            String sourceType          // "api" (live), "local" (cached), or "none"
    ) {}

    private ConditionDto() {}
}
