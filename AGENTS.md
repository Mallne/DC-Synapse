# Synapse Project Overview

**Synapse** is a core component of the **DiCentra Application Framework**, serving as a service discovery and "Catalyst" (service mesh/gateway) layer. It is built using the **Ktor** framework in **Kotlin** and integrates deeply with other DiCentra projects like **Aviator** and **Polyfill**.

---

## Core Technologies
- **Language:** Kotlin
- **Framework:** [Ktor](https://ktor.io/) (Netty server)
- **Dependency Injection:** [Koin](https://insert-koin.io/) (`@Single`, `@Factory`, `@ComponentScan`)
- **Database (ORM):** [Exposed](https://github.com/JetBrains/Exposed) with R2DBC (PostgreSQL)
- **Database Migrations:** [Flyway](https://flywaydb.org/)
- **Serialization:** [Kotlinx Serialization](https://github.com/Kotlin/kotlinx.serialization)
- **Service Mesh/Gateway:** DiCentra **Aviator** (OpenAPI-first discovery & gateway)
- **Security:** OIDC with Bearer token introspection
- **AI/LLM Integration:** Catalyst MCP capabilities (`mcpEnabled` flag)

---

## Architecture & Module Structure

The project follows a modular Gradle structure:
- **`:core`**: Contains the core logic, domain models, services, and route definitions. This is where most of the business logic (discovery, catalyst, scoper) lives.
- **`:host`**: The entry point for the application. It configures the Ktor `EngineMain` and initializes the modules defined in `:core`.

---

## Key Features & Endpoints

### 1. Service Discovery (`/services`)
Synapse provides a registry for service definitions. Services can be public or scoped (multi-tenant).
- `GET /services`: List available services (filterable by transformation type and grouping rule).
- `POST /services`: Register or update a service definition.
- `GET /services/{id}`: Retrieve a specific service definition.
- `DELETE /services/{id}`: Unregister a service.

### 2. Catalyst (`/catalyst`)
The "Catalyst" layer acts as a dynamic gateway that transforms registered service definitions into Aviator-executable OpenAPI path items via `CatalystGenerator`.
- `GET /catalyst`: Catalyst discovery — returns transformed OpenAPI specs for registered services (same query params as `/services`).
- **Note:** The `/catalyst` route is defined but currently **not wired** in `config/Routes.kt`.
- Catalyst generators inject `SynapsePlugin` (`aviator.plugin.synapse`) into Aviator plugin materialization.
- MCP support is available per-service via the `mcpEnabled` flag.

### 3. Multi-tenancy & Scoping
The project uses a "Scope" system to manage access to services. Users belong to scopes, and services can be restricted to specific scopes.

---

## Building and Running

### Prerequisites
- JDK 17 or higher.
- PostgreSQL (use `docker-compose up` to start a local instance).

### Key Commands
- **Build the project:** `./gradlew build`
- **Run the application:** `./gradlew :synapse:host:run` (monorepo) or `./gradlew :host:run` (standalone)
- **Run tests:** `./gradlew test`
- **Clean build:** `./gradlew clean`
- **Check for dependency updates:** `./gradlew versionCatalogUpdate` (using the `nl.littlerobots.version-catalog-update` plugin).

### Configuration
Configuration is managed via `host/src/main/resources/application.yaml`. Environment variables (like `DATA_USER`, `DATA_PASSWORD`) are used for sensitive information.

---

## Development Conventions

- **Coding Style:** Follows standard Kotlin idiomatic patterns.
- **Dependency Injection:** Uses Koin annotations (`@Single`, `@Factory`) and the Koin KSP compiler.
- **Error Handling:** Centralized exception handling via `DiCentraException` and Ktor status pages (configured in `Frameworks.kt` or `Routes.kt`).
- **Database:** Uses Exposed with the R2DBC/JDBC driver for PostgreSQL. Flyway manages schema migrations.
- **Testing:** Integration tests can be performed manually using `test.http` in the root directory. Automated tests should be added to `core/src/test` and `host/src/test`.

---

### 4. Aviator Integration
Synapse is deeply integrated with **DiCentra Aviator**:
- Every service is stored as an Aviator-compatible OpenAPI spec with `x-dicentra-aviator-*` extensions
- `MockConverter` validates OpenAPI docs on registration (`POST /services`)
- `AviatorServiceUtils.extractServiceLocators()` discovers locators from registered specs
- `CatalystGenerator` uses Aviator's KOAS model + plugin materialization to produce gateway endpoints
- Composite Gradle build links to `../aviator` for monorepo development

---

## Integration with DiCentra Framework
Synapse expects to be part of a larger monorepo context. It can substitute local project dependencies if `../aviator` or `../polyfill` directories exist (configured in `settings.gradle.kts`).

---

## Published Documentation (Notary)

The Synapse documentation is published in the DiCentra collection with the following hierarchy:

- [Synapse](https://docs.mallne.cloud/doc/synapse-hvlJpR3xLM) — Parent overview
  - [Architecture & Configuration](https://docs.mallne.cloud/doc/architecture-configuration-U9njnqrNdq) — Server layout, env vars, DB, security, DI
  - [Service Discovery — /services](https://docs.mallne.cloud/doc/service-discovery-services-H9e9UV3Nsb) — Register, query, update, delete service definitions
  - [Catalyst Gateway — /catalyst](https://docs.mallne.cloud/doc/catalyst-gateway-catalyst-WTHYfjvC1T) — Dynamic gateway, route transformation, MCP
  - [Multi-Tenancy & Scopes — /scope](https://docs.mallne.cloud/doc/multi-tenancy-scopes-scope-afO8CDHPCQ) — Scope CRUD, access control
  - [Aviator Integration](https://docs.mallne.cloud/doc/aviator-integration-JdsltjTQ80) — Full Aviator integration deep-dive
