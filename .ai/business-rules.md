# Business Rules -- Synapse

Rules an agent must respect when writing code for this module.

## Service Registration

- **Rule**: Every service MUST be stored as an Aviator-compatible OpenAPI spec with `x-dicentra-aviator-*` extensions.
- **Why**: The entire discovery and Catalyst pipeline depends on valid OpenAPI specs.

## Scope Enforcement

- **Rule**: Scope-based filtering must be enforced for all service and action access. Users belong to scopes; services can be restricted to specific scopes.
- **Why**: Multi-tenancy is a core feature. Scope bypasses break data isolation.

## Authentication

- **Rule**: Authentication is delegated to Synapse's `configureSecurity()`. Never implement custom auth logic outside this flow.
- **Why**: Consistent OIDC/Bearer token handling across all endpoints.

## Catalyst Gateway

- **Rule**: Catalyst generators must inject `SynapsePlugin` (`aviator.plugin.synapse`) into Aviator plugin materialization.
- **Why**: The plugin bridges Synapse's service registry with Aviator's execution pipeline.

## Database Migrations

- **Rule**: Use `V<Version>__<Description>.sql` naming convention. Migrations are auto-applied on startup.
- **Why**: Flyway requires strict naming. Auto-creation uses `DATA_AUTOCREATEDELTA`.

## Edge Cases

- **Catalyst route not wired**: The `/catalyst` route is defined but not currently registered in `Routes.kt`. Do not assume it is active.
- **Composite builds**: Synapse can use sibling project dependencies via `includeBuild` when inside the monorepo.

## Overrides

- None. Synapse follows root coding standards without module-specific overrides.
