# Synapse

**Stack**: Ktor server (Netty). Service discovery + Catalyst (MCP/LLM gateway).

> **Full docs**: [synapse/.ai/](.ai/) | [Notary](https://docs.mallne.cloud/doc/synapse-hvlJpR3xLM)

## Critical Rules

1. Every service MUST be stored as an Aviator-compatible OpenAPI spec
2. Scope-based filtering is mandatory for all service/action access
3. Authentication is delegated to Synapse's `configureSecurity()` -- no custom auth
4. DB migrations use `V<Version>__<Description>.sql` naming (Flyway)
5. Configuration via environment variables -- never hardcode secrets

## Build

```bash
./gradlew build
./gradlew :synapse:host:run
docker-compose up -d  # PostgreSQL required
```
