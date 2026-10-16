# Synapse

[![DiCentra](https://img.shields.io/badge/DiCentra-grey.svg)](https://code.mallne.cloud)
[![Kotlin](https://img.shields.io/badge/kotlin-grey.svg?logo=kotlin)](https://kotlinlang.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)

```text
 .oooooo..o
d8P'    `Y8
Y88bo.      oooo    ooo ooo. .oo.    .oooo.   oo.ooooo.   .oooo.o  .ooooo.
 `"Y8888o.   `88.  .8'  `888P"Y88b  `P  )88b   888' `88b d88(  "8 d88' `88b
     `"Y88b   `88..8'    888   888   .oP"888   888   888 `"Y88b.  888ooo888
oo     .d8P    `888'     888   888  d8(  888   888   888 o.  )88b 888    .o
8""88888P'      .8'     o888o o888o `Y888""8o  888bod8P' 8""888P' `Y8bod8P'
            .o..P'                             888
            `Y8P'                             o888o

A part of DiCentra by Mallne
```

**Synapse** is the core service discovery and Catalyst execution gateway of the **DiCentra Application Framework**.
It provides a centralized registry for Aviator-compatible services and an authorized HTTP gateway that executes
registered actions through Aviator.

---

## 🚀 Key Features

- **🔍 Service Discovery**: A robust registry for public and multi-tenant (scoped) services.
- **⚡ Catalyst Gateway**: Locator-based execution through the original Aviator provider pipeline.
- **🤖 MCP Server**: Planned separately; no MCP endpoint is currently provided by this HTTP milestone.
- **🔐 Secure by Design**: Integrated OIDC/JWT authentication with fine-grained scope-based access control.
- **🔗 Monorepo Ready**: Seamlessly integrates with [Aviator](https://github.com/mallne/DC-Aviator)
  and [Polyfill](https://github.com/mallne/DC-Polyfill).

---

## 🏗 Architecture

Synapse is built with a modular approach:

- **`:core`**: The heart of the system, containing domain logic, service definitions, and API routes.
- **`:host`**: The execution environment, handling server configuration and application startup.

---

## 🛠 Tech Stack

- **Language**: [Kotlin](https://kotlinlang.org/)
- **Server**: [Ktor](https://ktor.io/)
- **DI**: [Koin](https://insert-koin.io/)
- **Persistence**: [Exposed](https://github.com/JetBrains/Exposed) ORM with [PostgreSQL](https://www.postgresql.org/)
- **Migrations**: [Flyway](https://flywaydb.org/)
- **Serialization**: [Kotlinx Serialization](https://github.com/Kotlin/kotlinx.serialization)

---

## 🚦 Quick Start

### Prerequisites

- JDK 25 (monorepo Gradle toolchain; select it through `mise`)
- Docker (for database)

### Setup

1. **Start the database**:
   ```bash
   docker-compose up -d
   ```
2. **Configure environment**:
   Create a `.env` or set environment variables for `DATA_USER` and `DATA_PASSWORD`.

3. **Run the application**:
   ```bash
   ./gradlew :host:run
   ```

---

## 📖 API Overview

### Catalyst Endpoint

- `POST /catalyst/{selector}` executes the caller-visible original actions matching a canonical URL-safe Base64
  `ServiceLocator.toString()` selector. The JSON body is the version-1 `CatalystRequest` envelope; optional
  `identities` contains visible service-record IDs and overrides automatic filters. List results are flattened into
  a JSON array ordered by service ID. Any partial provider failure returns `502`; invalid input returns `400`.
- Discovery remains `GET /services`; there is no `GET /catalyst` locator-list endpoint.

Catalyst is fail-closed for outbound execution. Configure `CATALYST_ALLOWED_HOSTS` as a comma-separated list of exact
provider hostnames or IP addresses. Provider server URLs must use HTTPS on the standard port and must match an entry;
automatic HTTP redirects are disabled. Configure limits with `catalyst.max_targets`,
`catalyst.max_concurrent_targets`, `catalyst.max_request_bytes`, `catalyst.max_response_bytes`,
`catalyst.max_aggregate_response_bytes`, `catalyst.per_target_timeout_millis`, and
`catalyst.overall_timeout_millis` (environment-variable equivalents use the `CATALYST_` prefix).

### Discovery Endpoints

- `GET /services`: Discover available services based on your permissions.
- `POST /services`: Register or update a service definition.
- `GET /services/{id}`: Get details for a specific service.
- `DELETE /services/{id}`: Remove a service from the registry.

---

## 🤝 Contributing

Synapse is part of the DiCentra ecosystem. Contributions are welcome! Please check the main DiCentra documentation for
contribution guidelines.

---

<p align="center">
  Built with ❤️ by Mallne under the DiCentra umbrella
</p>
