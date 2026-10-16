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

## Activity Log

- Catalyst continuation: the focused Synapse test command passed with explicit JSON-null aggregation coverage (24 tests). Configuration now respects `catalyst.anonymous` through optional-auth route selection; Notary documentation was synchronized. The XML adapter aggregate test task could not run because `org.nodejs:node:24.16.0` was unavailable from repository resolution. This does not supersede the successful focused Synapse suite.
- Catalyst implementation-plan refresh: source and current test additions now include schema-bound typed inputs (required/missing/null/cardinality/supported constraints/body/path inputs), configurable request/provider-response/aggregate limits, HTTPS exact-host allowlisting, Koin/AreaAssist composition tests, and generic direct-vs-gateway parity scope. The newly present tests are not recorded as run or passed. Broader schema fidelity, outbound redirect/DNS/private-network handling, transaction/cache evidence, generated-client/OpenAPI reconciliation, and MCP remain open; no full acceptance is claimed.
- Catalyst verification update: `CatalystExecutorIntegrationTest.generic JSON output matches direct Aviator execution` exists and passed, so generic JSON direct-vs-gateway parity is covered; full AreaAssist Boundary/Query parity remains open. Final Synapse rerun passed after config YAML and explicit private-IP allowlist coverage. Environment YAML exposes documented `CATALYST_*` limits and comma-separated allowlist. External Notary/YouTrack sync is complete. Initial HTTP acceptance remains INCOMPLETE pending remaining gaps.
- Catalyst continuation verification: `CatalystGenerator` materializes `JsonAdapter` beside the active Synapse receiver plugin. Generated-client integration proves automatic plugin activation, generated POST, typed v1 envelope transport, and JSON response decoding; generator tests assert the OpenAPI 200 array schema and 400/404/502/503/504 responses. AreaAssist Server Koin codec tests roundtrip Boundary, field/content-checked Query list, and Boolean. Final Synapse core and standalone AreaAssist Server test commands passed; `git diff --check` is clean. This does not prove AreaAssist direct-vs-gateway provider parity or complete production OpenAPI/client codegen. Still open: DNS/egress/private-network behavior beyond HTTPS exact allowlisting, DB transaction-boundary integration, full schema/constraint fidelity, and other acceptance gaps. Notary and YouTrack AA-114 synchronized; milestone remains incomplete.
- Catalyst latest verification: Apache HttpClient 5 is the default; connection-time DNS resolution rechecks exact allowlist/public-address policy and supplies vetted addresses to the socket resolver, with redirects disabled. Rebinding unit coverage rejects a private second lookup. Nested typed lists require registered serializer codecs and recursively schema-validate; unsupported shape paths throw `IllegalArgumentException`; constraint tests were added. Generator tests cover request/response schemas and statuses 200, 400, 404, 413, 415, 500, 502, 503, 504. AreaAssist parity with a non-empty parcel passes. Synapse core and AreaAssist Server tests passed; diff check clean. Expanded opt-in DB/HTTP route + PostgreSQL transaction-ID test compiled but was skipped because Docker was unavailable after restart; earlier direct consecutive-R2DBC transaction test passed against Docker PostgreSQL. Run the expanded test and verify deployment egress firewall controls before acceptance. Do not claim full OpenAPI/schema construct parity; HTTP acceptance remains INCOMPLETE.
- Expanded opt-in database endpoint integration verification: the test compiles and is included in the Synapse suite, but is skipped unless `CATALYST_TEST_DATABASE_URL` is set. Its route uses actual `DatabaseService` transactions in both resolution and execution callbacks and compares `txid_current()` to verify transaction boundaries. The earlier, simpler database transaction test passed with Docker PostgreSQL; this expanded route test was not run because the Docker service is unavailable/start was denied. The full Synapse suite passes with the expanded test skipped. Acceptance remains INCOMPLETE until this expanded test is run and deployment egress ACLs are verified.
- Catalyst PostgreSQL endpoint verification: `DatabaseTransactionBoundaryIntegrationTest` passed against disposable PostgreSQL 17. The endpoint resolved and executed through actual `DatabaseService` callbacks, each in its own transaction, with distinct `txid_current()` IDs. Full `mise exec -- .\gradlew.bat --no-daemon :synapse:core:test` passed with `CATALYST_TEST_DATABASE_URL` set; AreaAssist Server tests passed; `git diff --check` is clean; temporary PostgreSQL container stopped. Catalyst acceptance remains INCOMPLETE only for deployment egress ACL verification and documented schema constructs beyond the supported validator.
