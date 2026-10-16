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
- Generated execution action: `POST /catalyst/{selector}`. The URL-safe selector identifies a `ServiceLocator` (locator plus flavour); discovery remains on `/services`.
- The route authorizes visible source actions, binds the JSON envelope against the original Aviator route input schema, and executes through the Aviator pipeline. It does not make provider calls outside Aviator.
- Request bytes, each provider response, aggregate response bytes, fanout target count/concurrency, and deadlines are bounded by Catalyst configuration. Provider destinations must use HTTPS and match an exact configured host/IP allowlist entry.
- Typed input binding handles supported required/missing/null/cardinality/constraint/body and path-level inputs; unsupported schemas/codecs fail closed. This is not full schema-fidelity acceptance.
- AreaAssist supplies ESRI/WFS plugin contributions through Koin at its composition root; generic Synapse does not depend on AreaAssist models. Composition/parity test presence is not evidence those tests passed.

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
