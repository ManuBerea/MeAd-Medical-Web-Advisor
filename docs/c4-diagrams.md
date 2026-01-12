# C4 Architecture Diagrams

These diagrams describe the service-oriented architecture of MeAd from a software engineering perspective. They cover the main modules, input/output formats, and data flows for end users.

## C4 Context Diagram

```mermaid
C4Context
title MeAd Medical Web Advisor - System Context

Person(user, "High-school student", "Explores medical conditions and geographic population insights.")

System(mead, "MeAd Medical Web Advisor", "Web application for medical and geography exploration.")

System_Ext(wikidata, "Wikidata SPARQL", "Linked data knowledge base.")
System_Ext(dbpedia, "DBpedia SPARQL", "Linked data knowledge base.")
System_Ext(wikidoc, "WikiDoc API", "Medical condition summaries.")
System_Ext(wikipedia, "Wikipedia REST API", "Geographic summaries.")

Rel(user, mead, "Uses", "HTTPS/Browser")
Rel(mead, wikidata, "Queries", "SPARQL")
Rel(mead, dbpedia, "Queries", "SPARQL")
Rel(mead, wikidoc, "Fetches summaries", "HTTP/JSON")
Rel(mead, wikipedia, "Fetches summaries", "HTTP/JSON")
```

## C4 Container Diagram

```mermaid
C4Container
title MeAd Medical Web Advisor - Containers

Person(user, "High-school student", "Explores conditions and geography.")

System_Boundary(mead, "MeAd Medical Web Advisor") {
  Container(ui, "mead-ui", "React", "Medical + geography explorers, list/detail views.")
  Container(conditions, "mead-conditions-service", "Spring Boot", "Conditions API, enrichment, SPARQL endpoint.")
  Container(geography, "mead-geography-service", "Spring Boot", "Regions API, enrichment, SPARQL endpoint.")
}

System_Ext(wikidata, "Wikidata SPARQL", "Linked data.")
System_Ext(dbpedia, "DBpedia SPARQL", "Linked data.")
System_Ext(wikidoc, "WikiDoc API", "Medical summaries, causes, risks.")
System_Ext(wikipedia, "Wikipedia REST API", "Geographic summaries.")

Rel(user, ui, "Browses", "HTTPS")
Rel(ui, conditions, "GET /conditions, /conditions/{id}", "JSON/HTTP")
Rel(ui, geography, "GET /regions, /regions/{id}", "JSON/HTTP")
Rel(conditions, wikidata, "SPARQL queries", "SPARQL/HTTP")
Rel(conditions, dbpedia, "SPARQL queries", "SPARQL/HTTP")
Rel(conditions, wikidoc, "Fetches summaries", "HTTP/JSON")
Rel(geography, wikidata, "SPARQL queries", "SPARQL/HTTP")
Rel(geography, dbpedia, "SPARQL queries", "SPARQL/HTTP")
Rel(geography, wikipedia, "Fetches summaries", "HTTP/JSON")
```

## C4 Component Diagram - Conditions Service

```mermaid
C4Component
title mead-conditions-service - Components

Container_Boundary(conditions, "mead-conditions-service") {
  Component(conditionsController, "ConditionsController", "Spring MVC", "Lists conditions and returns details.")
  Component(healthController, "HealthController", "Spring MVC", "Health check.")
  Component(sparqlController, "SparqlController", "Spring MVC", "Local SPARQL endpoint.")
  Component(conditionService, "ConditionService", "Service", "Orchestrates enrichment and DTO mapping.")
  Component(repo, "ConditionsRepository", "Repository", "Loads RDF data and caches conditions.")
  Component(rdfService, "RdfService", "Jena", "Loads Turtle into in-memory Dataset.")
  Component(wikidataClient, "WikidataClient", "SPARQL client", "Symptoms, risk factors, images.")
  Component(dbpediaClient, "DbpediaClient", "SPARQL client", "Descriptions, symptoms, images.")
  Component(wikidocLoader, "WikidocSnippetLoader", "HTTP client", "Summary and risk factors.")
}

Rel(conditionsController, conditionService, "Uses")
Rel(sparqlController, rdfService, "Queries")
Rel(conditionService, repo, "Reads")
Rel(repo, rdfService, "Uses Dataset")
Rel(conditionService, wikidataClient, "Enriches")
Rel(conditionService, dbpediaClient, "Enriches")
Rel(conditionService, wikidocLoader, "Fetches summaries")
```

## C4 Component Diagram - Geography Service

```mermaid
C4Component
title mead-geography-service - Components

Container_Boundary(geography, "mead-geography-service") {
  Component(regionsController, "RegionsController", "Spring MVC", "Lists regions and returns details.")
  Component(healthController, "HealthController", "Spring MVC", "Health check.")
  Component(sparqlController, "SparqlController", "Spring MVC", "Local SPARQL endpoint.")
  Component(geographyService, "GeographyService", "Service", "Orchestrates enrichment and DTO mapping.")
  Component(repo, "RegionsRepository", "Repository", "Loads RDF data and caches regions.")
  Component(rdfService, "RdfService", "Jena", "Loads Turtle into in-memory Dataset.")
  Component(wikidataClient, "WikidataClient", "SPARQL client", "Population, density, type, images.")
  Component(dbpediaClient, "DbpediaClient", "SPARQL client", "Descriptions, population, images.")
  Component(wikipediaLoader, "WikipediaSummaryLoader", "HTTP client", "Summary from Wikipedia.")
}

Rel(regionsController, geographyService, "Uses")
Rel(sparqlController, rdfService, "Queries")
Rel(geographyService, repo, "Reads")
Rel(repo, rdfService, "Uses Dataset")
Rel(geographyService, wikidataClient, "Enriches")
Rel(geographyService, dbpediaClient, "Enriches")
Rel(geographyService, wikipediaLoader, "Fetches summaries")
```

## Input/Output Data Formats

- Conditions API and Geography API: JSON responses for list/detail endpoints.
- SPARQL endpoints: `application/sparql-results+json` for SELECT/ASK and `text/turtle` for CONSTRUCT/DESCRIBE.
- External sources:
  - Wikidata/DBpedia via SPARQL.
  - WikiDoc and Wikipedia via HTTP JSON APIs.

## Data and Task Flow (high level)

1. User searches or selects an entity in the web UI.
2. UI calls the appropriate service endpoint (`/conditions/{id}` or `/regions/{id}`).
3. Service reads base entity data from the local RDF dataset.
4. Service enriches the entity using external sources.
5. Service returns a normalized JSON response to the UI.
