# Domain and Data Model

This document describes the internal data structures and RDF model used by MeAd.

## Overview
MeAd stores a small curated RDF dataset for medical conditions and geographic regions, then enriches each entity with data from external knowledge bases. The core domain is based on schema.org vocabularies:
- `schema:MedicalCondition` for conditions.
- `schema:Place` for geographic regions (cities, countries, continents).

Local RDF data provides identifiers, names, and `sameAs` links. The services use those links to query Wikidata and DBpedia, and to fetch text summaries from WikiDoc or Wikipedia.

## Local RDF datasets
Both services load Turtle files into an Apache Jena in-memory dataset at startup.

Conditions dataset:
- File: `mead-conditions-service/src/main/resources/rdf/conditions-data.ttl`
- RDF service: `mead-conditions-service/src/main/java/com/mead/conditions/service/RdfService.java`

Geography dataset:
- File: `mead-geography-service/src/main/resources/rdf/geography-data.ttl`
- RDF service: `mead-geography-service/src/main/java/com/mead/geography/rdf/RdfService.java`

### RDF shape (simplified)
```turtle
@prefix schema: <https://schema.org/> .
@prefix condition: <https://mead.example/condition/> .
@prefix region: <https://mead.example/region/> .

condition:asthma
  a schema:MedicalCondition ;
  schema:identifier "asthma" ;
  schema:name "Asthma"@en ;
  schema:sameAs <https://www.wikidata.org/entity/Q35869> ,
                <http://dbpedia.org/resource/Asthma> .

region:romania
  a schema:Place ;
  schema:identifier "romania" ;
  schema:name "Romania"@en ;
  schema:sameAs <https://www.wikidata.org/entity/Q218> ,
                <http://dbpedia.org/resource/Romania> .
```

## Core domain entities
### MedicalCondition
Stored in RDF and read into memory through `ConditionsRepository`:
- `identifier` (string)
- `name` (string)
- `sameAs` (list of URIs)

API models:
- `ConditionSummary`: id, name, sameAs
- `ConditionDetail`: context, id, type, identifier, name, description, images, symptoms, riskFactors, sameAs, wikidocSnippet

DTO source: `mead-conditions-service/src/main/java/com/mead/conditions/dto/ConditionDto.java`

### Region (Place)
Stored in RDF and read into memory through `RegionsRepository`:
- `identifier` (string)
- `name` (string)
- `sameAs` (list of URIs)

API models:
- `RegionSummary`: id, name, type, sameAs
- `RegionDetail`: context, id, type, identifier, name, description, populationTotal, populationDensity, culturalFactors, images, sameAs, wikipediaSnippet

DTO source: `mead-geography-service/src/main/java/com/mead/geography/dto/GeographyDto.java`

## Enrichment pipeline (conditions)
Inputs: `sameAs` links from RDF.

- **Wikidata**: description, symptoms, risk factors, images.
- **DBpedia**: description, symptoms, risk factors, images (fallback when Wikidata is empty).
- **WikiDoc**: summary paragraph, causes, risk factors; fallback symptoms from the main page when KB symptoms are missing.
- Risk factors are merged, normalized, and filtered to keep short UI-friendly labels.

Implementation entry point:
- `mead-conditions-service/src/main/java/com/mead/conditions/service/ConditionService.java`

## Enrichment pipeline (geography)
Inputs: `sameAs` links from RDF.

- **Wikidata**: description, population total, area-derived population density, cultural factors (languages, demonyms), images, region type (instance of).
- **DBpedia**: description, population total/density (fallback), cultural factors, images.
- **Wikipedia**: short summary via REST API.

Data quality enforcement:
- Regions must have **numeric** population total and population density. If values are missing or non-numeric, the region is excluded from the list and rejected on detail requests.

Implementation entry points:
- `mead-geography-service/src/main/java/com/mead/geography/service/GeographyService.java`
- `mead-geography-service/src/main/java/com/mead/geography/enrich/WikipediaSummaryLoader.java`

## Region type mapping
Region types are derived from Wikidata `instance of` (P31) labels, mapped to:
- `City`
- `Country`
- `Continent`
- Fallback: `Place`

## SPARQL access
Both services expose a local SPARQL endpoint backed by the in-memory Jena dataset:
- Conditions: `POST /mead-conditions-service/api/v1/sparql`
- Geography: `POST /mead-geography-service/api/v1/sparql`

SELECT queries require a LIMIT clause and are time-limited.

## External knowledge sources
- Wikidata SPARQL endpoint
- DBpedia SPARQL endpoint
- WikiDoc MediaWiki API (conditions)
- Wikipedia REST summary API (geography)

## Linked data usage
Entities use `schema:sameAs` to connect local identifiers to external URIs. This makes the dataset interoperable and allows enrichment using linked data principles (global identifiers, reuse of established vocabularies, and linking across datasets).
