# MeAd - Medical Web Advisor

MeAd is a multimedia learning experience for high-school students. It explores medical conditions and their impact on the human body, plus population context across towns, countries, and continents.

## Features
- Medical conditions explorer with symptoms, risk factors, images, and WikiDoc summaries.
- Geography explorer with total population, population density, cultural factors, and Wikipedia summaries.
- Linked Open Data (RDF/SPARQL) integration: Wikidata, DBpedia
- API integration: Wikipedia, WikiDoc
- Local SPARQL endpoints for the RDF datasets.

## Modules
- `mead-conditions-service` - medical conditions microservice
- `mead-geography-service` - geography population insights microservice
- `mead-ui` - React frontend

## Tech stack
- Backend: Spring Boot, Apache Jena, SPARQL
- Frontend: React
- Data sources: Wikidata, DBpedia, WikiDoc, Wikipedia

## REST APIs
Conditions API base: `http://localhost:8081/mead-conditions-service/api/v1`
- `GET /conditions`
- `GET /conditions/{id}`
- `GET /health`
- `POST /sparql`

Geography API base: `http://localhost:8082/mead-geography-service/api/v1`
- `GET /regions`
- `GET /regions/{id}`
- `GET /health`
- `POST /sparql`

OpenAPI specs:
- `contracts/openapi/openapi-conditions.yaml`
- `contracts/openapi/openapi-geography.yaml`

## Deliverables
- Scholarly report: `docs/scholarly-report.html`
- OpenAPI specs: `contracts/openapi/openapi-conditions.yaml`, `contracts/openapi/openapi-geography.yaml`

## Build and Run locally
Backend services (from each service folder):
- `./gradlew clean build bootRun`

Frontend:
- `npm run dev`

## Tests
- Conditions service: `./gradlew test`
- Geography service: `./gradlew test`

## Docker (all services)
- `docker compose up --build`
- Frontend: http://localhost:8080
- Conditions API: http://localhost:8081/mead-conditions-service/api/v1
- Geography API: http://localhost:8082/mead-geography-service/api/v1

If you deploy to a public host, rebuild the UI image with updated `VITE_CONDITIONS_API_BASE_URL` and `VITE_GEOGRAPHY_API_BASE_URL` in `docker-compose.yml`.

## Project Wiki
Public wiki: https://github.com/ManuBerea/MeAd-Medical-Web-Advisor/wiki

## Deployed application
URL: https://mead-ui.onrender.com/
