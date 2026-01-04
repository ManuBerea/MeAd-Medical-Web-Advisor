# Domain & Data Model (MeAd)

This document describes the **domain model** for **MeAd (Medical Web Advisor)** and how the app’s **managed data** is represented as RDF using **schema.org** vocabulary. It also clarifies which microservice owns which resources and how resources are identified (URIs).

## 1) Resource types and ownership

| Resource type | Meaning | Owned by | Primary storage |
|---|---|---|---|
| **Condition** | A medical condition students can explore (e.g., Asthma, Obesity) | `mead-conditions-service` | `mead-conditions-service/src/main/resources/rdf/conditions-data.ttl` |
| **Region** | A place used for geography context (Romania + regions) | `mead-geography-service` | `mead-geography-service/src/main/resources/rdf/geography-data.ttl` |
| **Indicator** | A definition of a measurable indicator (obesity prevalence, population density, PM2.5, urbanization) | `mead-geography-service` | `mead-geography-service/src/main/resources/rdf/geography-data.ttl` |
| **Observation** | A single data point for an indicator (value at a time, for a region) | `mead-geography-service` | `mead-geography-service/src/main/resources/rdf/geography-data.ttl` (+ optional CSV source files) |
| **WikiDoc snippet** | Short educational explanation text for a condition (attributed) | `mead-conditions-service` | text/markdown files + referenced/returned by the conditions API |

> Note: `mead-ai-service` does not own RDF resources; it consumes condition context (name/description/symptoms/risk factors) and returns an educational answer via `/api/chat`. 

---

## 2) Conditions domain (conditions service)

### 2.1 Condition (managed RDF + enriched API + RDFa)
**RDF class:** `schema:MedicalCondition`

**Managed RDF (conditions-data.ttl) defines:**
- `schema:identifier` — stable slug
- `schema:name` — label shown to students
- `schema:sameAs` — link to the Wikidata entity for this condition

**Condition detail returned by the API + rendered in the UI includes:**
- `schema:description` — short explanation (enriched/curated)
- `schema:image` — image URL (enriched)
- `schema:signOrSymptom` — symptoms (enriched)
- `schema:riskFactor` — risk factors (enriched)

- The UI embeds these fields as RDFa so pages contain structured data even when the managed TTL stays minimal.

**Example resources (URIs):**
- `<https://mead.example/condition/asthma>`
- `<https://mead.example/condition/seasonal-allergic-rhinitis>`
- `<https://mead.example/condition/type-2-diabetes>`
- `<https://mead.example/condition/obesity>`

**Where symptoms/risk factors come from:**
- The service enriches the condition using remote SPARQL queries (Wikidata/DBpedia) and returns the result as **JSON-LD-like** objects for the UI. 

### 2.2 WikiDoc snippet (educational content)
Each condition includes a short **WikiDoc snippet** (plain text or markdown) that is:
- written/selected for high-school readability,
- kept short and attributed (CC BY-SA where applicable),
- returned as a field in the condition detail response. 

---

## 3) Geography domain (geography service)

### 3.1 Region
**RDF class:** `schema:Place`

**Core fields:**
- `schema:name`
- `schema:containedInPlace` (region nesting, e.g., “București-Ilfov” in “Romania”)
- `schema:geo` (optional: lat/long or bounding info)

**Example resources (URIs):**
- `<https://mead.example/region/romania>`
- `<https://mead.example/region/bucuresti-ilfov>`
- `<https://mead.example/region/nord-est>`

For example, `bucuresti-ilfov` and `nord-est` are modeled as `schema:Place` instances contained in `romania`.

### 3.2 Indicator
Indicators are definitions of what is being measured.

**RDF class:** `schema:Dataset` (optionally also `schema:StatisticalVariable`)

**Core fields (in geography-data.ttl):**
- `schema:identifier`
- `schema:name`
- `schema:variableMeasured` – what is measured (e.g. “People per square kilometer”)
- `schema:measurementTechnique` – how the data is obtained (survey, census, monitoring, etc.)

**Example resources (URIs):**
- `<https://mead.example/indicator/obesity-prevalence>`
- `<https://mead.example/indicator/population-density>`
- `<https://mead.example/indicator/pm25>`
- `<https://mead.example/indicator/urbanization>`

Units are expressed on observation values. These indicators are connected to time series data (per year, per region), modeled as separate `schema:Observation` resources.

### 3.3 Observation (indicator data point)
A single data point (value + time + region).

**RDF classes:**
- `schema:Observation`
- `schema:QuantitativeValue` (used as the `schema:value` node when you want numeric value + units)

**Core fields:**
- `schema:observationAbout` — the region
- `schema:measuredProperty` — the indicator (preferably a `schema:StatisticalVariable`)
- `schema:observationDate` — date or year
- `schema:value` — number or a `schema:QuantitativeValue`
    - `schema:value` — numeric
    - `schema:unitText` or `schema:unitCode` — units

**Data sources:**
- The service may load observation values from CSV files (for fast iteration) and/or include them directly as RDF observations in `geography-data.ttl`. 

---

## 4) URI strategy (global identifiers)

Base namespace:
- `https://mead.example/`

Resource URIs:
- **Conditions:** `https://mead.example/condition/<slug>`
- **Regions:** `https://mead.example/region/<slug>`
- **Indicators:** `https://mead.example/indicator/<slug>`
- **Observations:** `https://mead.example/observation/<indicator-slug>/<region-slug>/<time>`

Examples:
- `https://mead.example/condition/asthma`
- `https://mead.example/region/romania`
- `https://mead.example/indicator/obesity-prevalence`
- `https://mead.example/observation/obesity-prevalence/romania/2022`

These identifiers are used consistently across:
- RDF files (`.ttl`)
- JSON-LD-like REST responses
- RDFa attributes emitted by the React UI 

---

## 5) RDF files (codebase locations)

- **Conditions RDF**
    - `mead-conditions-service/src/main/resources/rdf/conditions-data.ttl`
- **Geography RDF**
    - `mead-geography-service/src/main/resources/rdf/geography-data.ttl`

Each relevant service loads its RDF into an in-memory Jena model and exposes a `/api/sparql` endpoint over that model.

## 6) Vocabularies Used

The MeAd RDF model reuses existing vocabularies, mainly **schema.org**, plus links to **Wikidata**.

### 3.1 `schema.org`

MeAd uses the following `schema.org` classes and properties:

- **Classes**
    - `schema:MedicalCondition`
    - `schema:Place`
    - `schema:Dataset`
    - `schema:Observation`

- **Properties**
    - `schema:name`
    - `schema:sameAs`
    - `schema:containedInPlace`
    - `schema:variableMeasured`
    - `schema:measurementTechnique`
    - `schema:observationAbout`
    - `schema:measuredProperty`
    - `schema:observationDate`
    - `schema:value`

Condition detail pages and API payloads also use:

- `schema:description`
- `schema:image`
- `schema:signOrSymptom`
- `schema:riskFactor`

### 3.2 Wikidata links

Internal MeAd resources link to external Wikidata entities using:

- `schema:sameAs`

Example:

```turtle
condition:asthma
  a schema:MedicalCondition ;
  schema:name "Asthma"@en ;
  schema:sameAs <https://www.wikidata.org/entity/Q35869> .
