# Architecture -- Synapse

## Purpose

Synapse is a service discovery and Catalyst (MCP/LLM gateway) server. It provides a registry for API service definitions and dynamically transforms them into Aviator-executable OpenAPI path items.

## Tech Stack

- **Language:** Kotlin (JVM)
- **Framework:** Ktor (Netty server)
- **DI:** Koin (with KSP)
- **Database:** PostgreSQL via Exposed (R2DBC/JDBC)
- **Migrations:** Flyway
- **Serialization:** kotlinx.serialization
- **Security:** OIDC with Bearer token introspection
- **API Framework:** Aviator (OpenAPI-first discovery and gateway)

## Project Structure

```
synapse/
+-- core/                    # Business logic, domain models, services, routes
|   +-- config/              # Routes.kt, Frameworks.kt
|   +-- service/             # APIDBService, ScopeService
|   +-- model/               # Domain models
+-- host/                    # Entry point (Ktor EngineMain)
    +-- src/main/resources/
        +-- application.yaml  # Configuration
```

## API Surface

### Service Discovery (`/services`)
- `GET /services` -- List services (filterable by transformation type, grouping rule)
- `POST /services` -- Register/update a service definition
- `GET /services/{id}` -- Get a specific service
- `DELETE /services/{id}` -- Unregister a service

### Catalyst (`/catalyst`)
- `GET /catalyst` -- Returns transformed OpenAPI specs for registered services
- Note: Route is defined but currently **not wired** in `config/Routes.kt`

### Multi-tenancy (`/scope`)
- Scope-based access control for services and users

## Data Flow

1. Services are registered as Aviator-compatible OpenAPI specs with `x-dicentra-aviator-*` extensions
2. `MockConverter` validates OpenAPI docs on registration
3. `CatalystGenerator` uses Aviator's KOAS model to produce gateway endpoints
4. `SynapsePlugin` is injected into Aviator plugin materialization

## Dependencies on Other Modules

- **Aviator** -- Core API framework, KOAS model, plugin system
- **Polyfill** -- Utility functions

## Non-negotiable Rules

- Every service MUST be stored as an Aviator-compatible OpenAPI spec
- Use `DiCentraException` for all custom errors
- Database migrations use `V<Version>__<Description>.sql` naming
- Configuration via environment variables (never hardcode secrets)
