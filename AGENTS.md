# Synapse Project Overview

**Synapse** is a core component of the **DiCentra Application Framework**, serving as a service discovery and "Catalyst" (service mesh/gateway) layer. It is built using the **Ktor** framework in **Kotlin** and integrates deeply with other DiCentra projects like **Aviator** and **Polyfill**.

---

## Core Technologies
- **Language:** Kotlin
- **Framework:** [Ktor](https://ktor.io/) (Server with Netty, Client with Apache)
- **Dependency Injection:** [Koin](https://insert-koin.io/)
- **Database (ORM):** [Exposed](https://github.com/JetBrains/Exposed) (PostgreSQL backend)
- **Database Migrations:** [Flyway](https://flywaydb.org/)
- **Serialization:** [Kotlinx Serialization](https://github.com/Kotlin/kotlinx.serialization)
- **Service Mesh/Gateway:** DiCentra **Aviator**
- **Security:** OIDC with JWT (Bearer tokens)
- **AI/LLM Integration:** [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) server integration

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
The "Catalyst" layer acts as a dynamic gateway or service locator.
- `GET /catalyst`: Discovery endpoint specifically for Catalyst locators.
- `POST /catalyst/aggregate/{locator}`: (Inferred) Aggregate requests across multiple service locators.
- `POST /catalyst/request/{id}`: (Inferred) Send a tailored request to a specific service.
- `GET /catalyst/mcp`: **MCP Server** endpoint, allowing LLM agents to interact with the service mesh using the Model Context Protocol.

### 3. Multi-tenancy & Scoping
The project uses a "Scope" system to manage access to services. Users belong to scopes, and services can be restricted to specific scopes.

---

## Building and Running

### Prerequisites
- JDK 17 or higher.
- PostgreSQL (use `docker-compose up` to start a local instance).

### Key Commands
- **Build the project:** `./gradlew build`
- **Run the application:** `./gradlew :host:run`
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

## Integration with DiCentra Framework
Synapse expects to be part of a larger monorepo context. It can substitute local project dependencies if `../aviator` or `../polyfill` directories exist (configured in `settings.gradle.kts`).
