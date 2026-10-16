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
- **Rule**: Execution uses generated `POST /catalyst/{selector}` actions. Bind inputs against the original action schema; enforce required/missing/null rules, cardinality, supported constraints, body shape, and path-level parameters. Reject unsupported schemas/codecs rather than silently coercing or dropping values.
- **Rule**: Configure request, provider-response, aggregate-response, fanout, and deadline bounds. Provider destinations must be HTTPS, credential-free, standard port, and an exact configured host/IP allowlist match.
- **Why**: Schema binding preserves the declared action contract; explicit resource and destination bounds constrain gateway execution. These controls do not by themselves prove full OpenAPI fidelity, DNS/redirect safety, or end-to-end acceptance.

## Database Migrations

- **Rule**: Use `V<Version>__<Description>.sql` naming convention. Migrations are auto-applied on startup.
- **Why**: Flyway requires strict naming. Auto-creation uses `DATA_AUTOCREATEDELTA`.

## Edge Cases

- **Catalyst acceptance**: Presence of composition/parity tests does not establish that they passed. Do not claim complete acceptance without recorded execution, and keep broader schema fidelity, redirect/DNS/private-network policy, transaction/cache lifecycle, client automation, and MCP gaps explicit.
- **Composite builds**: Synapse can use sibling project dependencies via `includeBuild` when inside the monorepo.

## Overrides

- None. Synapse follows root coding standards without module-specific overrides.
