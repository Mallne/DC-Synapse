# Catalyst Implementation Plan

**Status:** Locator-based HTTP execution and fanout, typed schema binding, configurable transport limits, and fail-closed outbound HTTPS allowlisting are implemented. Apache HttpClient 5 is the default; connection-time DNS resolution rechecks exact allowlist/public-address policy and returns vetted addresses to the socket resolver. Redirects are disabled, and a DNS-rebinding unit test rejects a private second lookup. Nested list typed inputs require registered serializer codecs and recursively schema-validate; unsupported shape paths throw `IllegalArgumentException`, with constraint tests added. Synapse core and AreaAssist Server tests passed. `CatalystGenerator` tests cover request/response schemas and documented statuses 200, 400, 404, 413, 415, 500, 502, 503, 504. AreaAssist parity with a non-empty parcel passes. The endpoint-level opt-in DB/HTTP route + PostgreSQL transaction-ID integration test passed against disposable PostgreSQL 17 with actual DatabaseService resolution/execution callback transactions and different `txid_current()` IDs. The full Synapse core test suite passed with `CATALYST_TEST_DATABASE_URL` set; AreaAssist Server tests passed; `git diff --check` is clean; the temporary PostgreSQL container was stopped. Acceptance remains incomplete only for deployment egress ACL verification and documented schema constructs beyond the supported validator. Initial HTTP acceptance remains INCOMPLETE only for deployment egress ACL verification and documented schema constructs beyond the supported validator. MCP remains a separate milestone.
**Issue:** [AA-114 — Catalyst](https://dicentra.youtrack.cloud/issue/AA-114)
**Scope:** Synapse gateway, Aviator integration, shared-plugin composition, and subsequent MCP milestone.

## Implementation progress/status

`GET /catalyst` was removed; discovery remains at `/services`. `POST /catalyst/{selector}` takes a canonical URL-safe Base64 encoding of `ServiceLocator.toString()` (locator plus flavour), with no API service ID in the path. Duplicate generated paths for one locator are intentionally coalesced in the OpenAPI `paths` map. The handler snapshots public plus authenticated user scopes in a DB transaction, rejects invalid users, resolves caller-visible eligible locator candidates, releases the transaction before execution, and passes cancellation through. `CatalystConfig.anonymous` controls whether Ktor authentication is optional. Optional request `identities` are APIServiceDTO/service-record IDs, restricted to visible locator candidates, and override automatic filters; missing or inaccessible requested identities return non-disclosing 404. Generic statuses include 400 for bad envelopes/codecs, 503 for missing required plugins, 502 for partial target failure or a missing Aviator context result, and 500 for unexpected failures. An explicit provider JSON `null` is preserved as a JSON array element (`[null]`); this does not settle other null/output behavior or the complete partial-result contract.

`CatalystActionFilter` extensions run by ascending priority then identity; identities must be unique. The first filter returning a non-empty matching selection wins. If none selects candidates, all locator candidates are queried. AreaAssist's filter ports app `ApiServiceUseCase` parcel selection: boundary point-in-polygon against `ParcelServiceOptions.bounds`, parcel-ID prefix to Bundesland/deBId when `canInferWithParcelId`, otherwise all candidates. AreaAssist registers the filter beside ESRI/WFS plugin/codec contributions. `CatalystExecutor` queries selected candidate actions deterministically by service ID through Aviator, flattens list/JsonArray outputs into one JSON array, continues after per-target failures, and returns 502 for partial failure. It selects only identities materialized on each original route, rejects missing or duplicate contribution registrations, creates fresh plugin instances, and executes through `KtorAviatorServiceConverter` and Aviator's pipeline. A shared `HttpClient` closes on `ApplicationStopped`. `configureFrameworks(vararg hostModules)` loads Synapse DI and supplied host Koin modules; generic hosts must contribute materialized plugins or execution fails closed.

Typed transport support is implemented across Aviator, its Synapse plugin, and AreaAssist Shared. The current Synapse binder resolves v1 typed inputs against the original route's Aviator input schema, checks required/unknown inputs, single-versus-multi cardinality, supported primitive/enum/structured codecs, nullability, supported schema constraints, and JSON body shape/required properties. Path-level and other original action parameters are included through the route input schema; unsupported/nested shapes fail explicitly. This is not proof of complete OpenAPI/Aviator constraint fidelity. Aviator execution contexts expose an optional result serializer; `finalResultToJsonElement` uses it when present and otherwise falls back to the original output serializer. AreaAssist Shared's ESRI and WFS plugins set `ListSerializer(ParcelCrateEntity.serializer())` after replacing the result. Focused verification:

```powershell
mise exec -- .\gradlew.bat --no-daemon :aviator:plugins:synapse:jvmTest
mise exec -- .\gradlew.bat --no-daemon :areaassist:shared:jvmTest
mise exec -- .\gradlew.bat --no-daemon :synapse:core:test
```

Earlier `:core:test` coverage includes route cases, ordered/invalid filters, Aviator MockEngine JSON aggregation/partial failure/XML adapter/JsonNull, and fanout target cap/concurrency/order/flattening/deadlines/cancellation. Final Synapse core tests passed after config YAML and explicit private-IP allowlist coverage. `CatalystExecutorIntegrationTest.generic JSON output matches direct Aviator execution` passed; generic JSON parity is covered. A generated-client integration test additionally proves generated-definition plugin activation, generated POST invocation, typed v1 envelope transport, and JSON response decoding. Generator tests assert the OpenAPI 200 array schema and 400/404/502/503/504 response statuses. Standalone AreaAssist Server tests pass, including Koin codec roundtrips for Boundary, field/content-checked Query list, and Boolean. These Koin/wire tests do not establish full direct-vs-gateway provider parity for AreaAssist Boundary/Query, which remains open. The environment YAML exposes all documented `CATALYST_*` limit values and the comma-separated allowlist. Earlier Aviator Synapse plugin JVM tests passed. Aviator Ktor client JVM task passed only on isolated rerun after an overlapping invocation failed resolving `koas`; its tests were NO-SOURCE, so it proves compilation only. Request bytes, per-target response bytes, and aggregate response bytes have configurable ceilings; provider URLs require HTTPS, no embedded credentials, default HTTPS port, and exact normalized host/IP allowlist membership. Remaining gaps include complete schema fidelity, DNS/egress/private-network/redirect policy, true transaction-boundary integration, cache lifecycle, full production OpenAPI/client codegen across schemas/error payloads/identity, and complete response/error contract. External Notary/YouTrack synchronization is complete. MCP remains a separate milestone.

## 1. Intent and settled boundaries

Catalyst is a generic **server-side Aviator execution gateway**. A client sends an agnostic request to Synapse over HTTP; Catalyst resolves authorized original service actions, executes them through Aviator, and returns results produced by the Aviator plugin pipeline. Provider request adaptation and response normalization belong entirely to Aviator plugins, not Catalyst.

AA-114 describes app-like querying, aggregation of normalized outputs, scopes, a detached app/server interface usable by AreaAssist, and future SSE-ready MCP. The clarified boundary above supersedes any interpretation that Catalyst itself normalizes provider data.

### Goals

- Execute registered Aviator actions remotely with parity to direct Aviator execution.
- Let the existing Aviator Synapse client/receiving plugin automatically activate and configure from generated Synapse/Catalyst definitions, without per-request application-side gateway wiring.
- Resolve single actions and locator groups without ambiguous selection or silent definition loss.
- Collect post-plugin outputs from authorized services in a locator group under an explicitly agreed response contract.
- Keep AreaAssist's agnostic parameters and existing shared ESRI/WFS plugins usable across direct and gateway execution.
- Preserve scope isolation, upstream credential separation, cancellation, and bounded resource use.

### Non-goals

- A bespoke normalization engine, GIS-specific branches, parcel deduplication, coordinate merging, or a new semantic aggregation framework.
- Extracting a new normalization core from plugins that already live in AreaAssist Shared.
- Arbitrary remote plugin loading, runtime code installation from OpenAPI, or a global Aviator plugin-system redesign.
- Compatibility with **Aerilion**: it is only a rough concept with no implementation or compatibility requirement.
- Implementing MCP or SSE in the initial HTTP execution milestone. MCP later adapts the same executor, not a parallel execution stack.
- Changing unrelated registry behavior or repairing unrelated authentication/build issues as part of gateway implementation.

## 2. Verified current state

Paths below are repository-root-relative. Observations come from source inspection, not successful builds or tests.

| File | Current behavior / implication |
|------|-------------------------------|
| `synapse/core/src/main/kotlin/cloud/mallne/dicentra/synapse/config/Routes.kt` | Registers Catalyst discovery and action execution routes when `SynapseConfig.catalyst.enabled` is true. |
| `synapse/core/src/main/kotlin/cloud/mallne/dicentra/synapse/routes/Catalyst.kt` | Defines `POST /catalyst/{selector}`. Execution snapshots public plus authenticated user scopes transactionally, resolves visible locator candidates, then executes after transaction release with non-disclosing errors. |
| `synapse/core/src/main/kotlin/cloud/mallne/dicentra/synapse/service/CatalystGenerator.kt` | Generates locator-based action paths using canonical URL-safe Base64 of `ServiceLocator.toString()`; duplicate generated paths for a locator are intentionally coalesced in OpenAPI `paths`. |
| `synapse/core/src/main/kotlin/cloud/mallne/dicentra/synapse/model/dto/ServiceBundle.kt` | Groups transformed routes, passes a service ID for `Single`, and assembles wrappers. One service ID can represent multiple original routes. |
| `synapse/core/src/main/kotlin/cloud/mallne/dicentra/synapse/model/dto/APIServiceDTO.kt` | Extracts locators/routes and evaluates native/Catalyst transformation, preference, and grouping. Declares `nativeTransformable`, `catalystTransformable`, `explicitOnly`, and `mcpEnabled`; `explicitOnly` selects single-service grouping. |
| `synapse/core/src/main/kotlin/cloud/mallne/dicentra/synapse/service/APIDBService.kt` | Persists transformation and MCP flags, but has no `explicitOnly` column or create/read/update mapping. A stored DTO therefore loses that setting. Address only as necessary for eligibility semantics, with a scoped migration. |
| `synapse/core/src/main/kotlin/cloud/mallne/dicentra/synapse/config/Security.kt` | `configureSecurity()` owns bearer authentication/introspection. Reuse it; do not invent Catalyst authentication. |
| `synapse/README.md` | Advertises aggregate/request/MCP routes different from generated query-selector routes, and describes GET as a locator list. These are not evidence of implemented endpoints. |
| `aviator/core/src/commonMain/kotlin/cloud/mallne/dicentra/aviator/core/APIToServiceConverter.kt` | Crystallizes an installed registry, filters by `serviceFilter`, and calls `requestReconfigure` only on matching installed instances. Materialization does **not** instantiate missing plugins. |
| `aviator/clients/ktor/src/commonMain/kotlin/cloud/mallne/dicentra/aviator/client/ktor/KtorAviatorServiceConverter.kt` | Builds services per route; gathers plugin adapters/deserializers alongside common ones. |
| `aviator/clients/ktor/src/commonMain/kotlin/cloud/mallne/dicentra/aviator/client/ktor/KtorAviatorService.kt` | Exposes `requestContextful` and result-returning requests. Use the existing execution path rather than implementing provider HTTP in Catalyst. |
| `aviator/core/src/commonMain/kotlin/cloud/mallne/dicentra/aviator/core/execution/AviatorExecutionPipeline.kt` | Runs plugin hooks throughout invocation, validation, matching, forming, requesting, painting, and finishing. Plugins can change execution context and final result. |
| Aviator core request parameter wrappers and transport values | Serializer-aware `serializedSingle` / `serializedMulti` wrappers carry JSON-typed values with cardinality and serializer descriptor `serialName`. |
| `aviator/plugins/synapse/src/commonMain/kotlin/cloud/mallne/dicentra/aviator/plugin/synapse/SynapsePlugin.kt` | Emits v2 `typedParameters` while retaining legacy string-list fields for compatibility. Installation availability is separate from activation. |
| Aviator Synapse `CatalystParameterCodecs` and `CatalystRequest.toRequestParameters` | Decode v1 legacy strings and v2 typed JSON values; this decoder is not yet called by the Synapse execution route. |
| `aviator/plugins/weaver/src/commonMain/kotlin/cloud/mallne/dicentra/aviator/plugin/weaver/WeaverPlugin.kt` | Transforms responses after `PaintingResponse`, consulting the network chain and potentially replacing `context.result`. |
| `areaassist/shared/src/commonMain/kotlin/cloud/mallne/dicentra/areaassist/aviator/AreaAssistParameters.kt` | Boundary and query helper parameters attach serializers for typed transport. |
| `areaassist/shared/src/commonMain/kotlin/cloud/mallne/dicentra/areaassist/aviator/esri/EsriAdapterPluginInstance.kt` | Reads typed `Boundary`, `List<Query>`, and `Boolean` before `PathMatching`; replaces the result after `PaintingResponse` with parcel output. |
| `areaassist/shared/src/commonMain/kotlin/cloud/mallne/dicentra/areaassist/aviator/wfs/WfsAdapterPluginInstance.kt` | Adapts typed parameters before `PathMatching`, also modifies the request after `FormingRequest`, and replaces the painted result with parcel output. |
| `areaassist/shared/build.gradle.kts` | Already declares JVM and shared Aviator/geospatial dependencies; no app, Room, or UI dependency is declared here. Reuse must still verify transitive dependency and initialization requirements. WFS composition requires its CRS registry. |
| `synapse/core/build.gradle.kts`, `synapse/settings.gradle.kts` | Core already declares Aviator clients/adapters/plugins; sibling Aviator/Polyfill composite substitution exists. Shared AreaAssist composition is not currently wired here. |

There is no verified generic cross-service semantic merge strategy in Aviator. Existing pipeline response handling must not be described as such. Existing `.ai/` descriptions of Catalyst as an MCP/LLM gateway are broad legacy terminology; this plan defines the execution-first scope and tracks MCP separately.

## 3. Target architecture and data flows

### Direct execution baseline

```text
App agnostic typed parameters + body
  -> original OpenAPI-derived Aviator service
  -> installed, route-configured plugins adapt provider request
  -> upstream HTTP via Aviator
  -> response adapters / Weaver / domain plugins
  -> final plugin-produced typed result -> app
```

### Catalyst execution

```text
App agnostic parameters + body
  -> generated gateway Aviator service
  -> installed Synapse client plugin automatically activated/configured
  -> agreed Catalyst HTTP request envelope
  -> Synapse configureSecurity + authorized action resolution
  -> snapshot of ORIGINAL stored OpenAPI/action metadata
  -> reconstruct typed invocation through agreed codecs/contracts
  -> server Aviator service + installed original-route plugins
  -> upstream HTTP via Aviator (server-owned upstream credentials)
  -> complete plugin pipeline -> serialize FINAL result
  -> agreed single/group response contract
  -> gateway client deserialization -> app result
```

The client receives public gateway definitions and final outputs. The server retains original provider definitions, private options, provider adapters/transforms, and upstream credentials. HTTP handlers manage transport only; a transport-independent executor performs resolution and orchestration so later MCP can reuse it.

### Ownership and automatic configuration

- **Gateway client:** Synapse request envelope and decoding of the public output contract; authentication to Synapse. Do not run provider request adapters or server-owned normalization a second time.
- **Gateway server:** Original-route request adaptation, network execution, response adaptation/normalization, and final-result serialization, all through Aviator plugin contracts.
- **Composition root:** Explicitly install supported plugin code and serializers/adapters once. Generated metadata then activates/configures already available instances. Define any minimal receiving-plugin bootstrap needed so users do not manually toggle `active` for each discovered service.
- **Generator:** Preserve useful public options and declared result contracts; separate private provider configuration and server-only plugin materialization from client configuration. Current wholesale copying and Weaver-only stripping are insufficient. Decide ownership by an explicit contract/capability or composition policy, not a settled hardcoded plugin-name blacklist.
- **Missing plugin:** Resolve the required/optional distinction and fail/omit policy before execution work. Do not silently claim normalized parity when a required installed plugin is absent or filtered out.

Server deployment may compose AreaAssist Shared's existing ESRI/WFS plugins and required geospatial services. Keep generic Catalyst orchestration independent of AreaAssist models. Inspect publication coordinates, composite substitutions, transitive dependencies, and Koin initialization before selecting a composition location; do not add app/Room/UI dependencies or introduce a bespoke replacement normalizer.

## 4. Contract decisions required before implementation

### Canonical HTTP and OpenAPI

Do not assume either README routes or generator paths are authoritative. Select and record one canonical discovery and execution contract, then change route registration, generator, client configuration, OpenAPI, tests, and documentation together.

- Decide whether execution uses selector query parameters or distinct single/group paths. Validate OpenAPI path modeling: query selectors should be declared as parameters rather than treated as distinct OpenAPI path keys; account for Aviator's actual route matcher behavior with tests.
- Preserve a clear distinction between discovery response and execution response. Specify HTTP methods, content types, input schema, selectors, outputs, security requirements, and error statuses.
- [x] Canonical locator selector: `/catalyst/{selector}`. The selector encodes `ServiceLocator.toString()` (locator plus flavour); `CatalystActionSelector` implements encoding/decoding and malformed-selector tests.
 - Locator selectors encode `ServiceLocator.toString()` (locator plus flavour), without service ID. Resolve all eligible caller-visible services for that locator, requiring at most one source route per locator within each service and excluding Catalyst wrappers. Duplicate generated locator paths are intentionally coalesced in OpenAPI `paths`. Optional `identities` restrict to visible APIServiceDTO/service-record IDs and override filters; inaccessible/missing IDs return non-disclosing 404.
- [x] Ensure exact generated action path uniqueness: action URLs include encoded service ID plus encoded method/newline/original path, so actions sharing a locator do not collide by path. This completion covers exact action-path uniqueness only; it does not complete locator-group membership/fanout semantics, all collision classes, or broader generator collision validation.
- Source resolution uses **only original registered definitions**, never Catalyst-generated wrappers. Reject recursive gateway targets and define validation for wrapper re-registration/indirect loops without excluding legitimate providers arbitrarily.

### Typed input roundtrip: blocking spike

**Selected plan — option 1:** transport typed JSON parameter values, not `toString()` stringification. Implemented Aviator wrappers `serializedSingle` / `serializedMulti` carry JSON values, cardinality, and serializer descriptor `serialName`; the Synapse plugin emits v2 `typedParameters` and retains legacy string-list fields. `CatalystParameterCodecs` and `CatalystRequest.toRequestParameters` decode v1 legacy strings and v2 typed JSON into request parameters, and the Catalyst executor uses registered codecs during execution. This does not complete broader schema validation or action binding.

**Remaining integration work:** deployments must supply serializers/codecs for structured parameters. The executor now connects binding to resolved original actions and validates supported input schemas, including required/absent, explicit null, cardinality, supported constraints, JSON bodies, and path-level parameters. Unsupported codec/schema shapes fail closed. Full schema fidelity and broader regression coverage remain open; this is not a claim that every OpenAPI schema is supported.

Design and validation checklist:

- [x] Add v2 typed JSON parameter transport with cardinality/serializer identity and preserve v1 legacy string decoding in the Catalyst parameter codec.
- [x] Resolve typed parameters against the original action's Aviator route input schema and registered codecs; construct `RequestParameter.Single` or `RequestParameter.Multi` with schema-defined cardinality. Path-level inputs are validated/bound from the route schema, not inferred from generated gateway schemas.
- [x] Distinguish missing parameters from explicit JSON null; enforce requiredness, nullable schema union, and single/multi cardinality for supported inputs.
- [ ] Fail explicitly for unsupported schemas/codecs, unknown parameter names, invalid JSON, and incompatible value shapes; never silently coerce, drop values, or substitute empty queries/default geometry.
- [ ] Complete regression matrix for Boolean; structured Boundary/CRS; `List<Query>` including every supported query variant; repeated strings; absent/null; non-null JSON body; path-level parameters; legacy clients; and all supported constraints. Current binder test additions are not yet recorded as run.
- [ ] Keep any fallback codec metadata narrowly scoped to the proven schema gap, backwards compatible, and covered by the same regression suite.

### Output and grouping: blocking spike

A naive `request<JsonElement>` may not safely accept plugins that assign concrete `List<ParcelCrateEntity>` values to `context.result`. Determine the actual generic/runtime behavior before choosing an invocation type or serializer.

- Prove serialization of the **final** plugin-produced result after all stages, including typed lists, objects, scalar JSON, null/empty results, and plugin failures. Do not serialize only the raw upstream body or pre-transform network-chain payload.
- [x] Establish how the executor obtains the appropriate serializer/type contract without embedding AreaAssist-specific casts. Aviator contexts expose optional `resultSerializer`; `finalResultToJsonElement` uses it when present and falls back to the original output serializer. ESRI/WFS AreaAssist plugins set the parcel-list serializer after replacing the result. This serializer SPIKE does not establish end-to-end execution/output behavior.
 - Current behavior flattens list/JsonArray results into one JSON array, orders targets by service ID, continues after per-target errors, and returns 502 on any partial failure. An explicit JSON null returned by a provider is preserved as an array element (`[null]`); a missing result from the Aviator execution context is an upstream failure (502). These verified cases do not settle all null/output semantics. Single-target shape, partial-failure payload/contract, and other output contract decisions remain open.
- Validate each proposed shape against existing typed clients. Do not impose an arbitrary aggregate envelope or flatten mixed shapes merely because it is convenient server-side.
- Locator aggregation means fanout/collection of plugin-produced outputs under the agreed contract. It does not mean domain deduplication, coordinate reconciliation, or invented semantic merging.

## 5. Authorization, eligibility, and operations

- Reuse `configureSecurity()` and existing principal/scope services. `CatalystConfig.anonymous` controls whether Ktor authentication is optional. Define anonymous public-service execution consistently with optional-auth discovery; invalid authentication must not widen access.
- Authorize **every execution**, including guessed service/action IDs and each locator fanout member. Discovery visibility is not proof of later authorization. Specify non-disclosing unauthorized/not-found responses.
- Resolve current visibility and eligibility before executing cached services; cache keys/invalidation must not let an old definition or another tenant's configuration bypass policy. Snapshot only the authorized original data required for this invocation.
- `catalystTransformable` controls gateway eligibility. Define `explicitOnly` behavior for direct selection versus locator groups and restore persistence if required. `nativeTransformable` governs native discovery, not an implicit grant to gateway execution. `preferredTransform` is a discovery preference, not an authorization override. `mcpEnabled` governs later MCP exposure, not ordinary HTTP execution. Preserve `builtin` behavior without inventing new grants.
- Never automatically forward the caller's Synapse bearer token or arbitrary incoming headers to providers. Use server-configured upstream authentication; delegated credentials require a separately explicit policy. Keep secrets out of generated definitions, errors, logs, and metrics.
- Resolve destinations from trusted registered definitions, not caller-provided URLs. Define outbound scheme/host policy, redirect validation, and private-network/SSRF treatment appropriate to deployments.
- Materialize authorized DB snapshots in short transactions, then leave transaction scope **before any provider network I/O**. No DB connection may be held throughout fanout.
- Bound fanout concurrency, total targets, request/response sizes, per-provider timeouts, and overall request deadline. Propagate structured cancellation to child requests; never convert caller cancellation into a normal aggregate success. Define sibling cancellation according to the agreed failure contract.
- Give reusable Aviator HTTP clients/services explicit application lifecycle ownership; close owned clients on shutdown, avoid per-target unclosed clients, and isolate per-request mutable contexts/plugin reconfiguration across concurrent requests.
- Avoid automatic retry of non-idempotent actions. Any retry policy must derive from action semantics and the contract, not a blanket gateway retry.
- Use centralized error handling and `DiCentraException` conventions. Record redacted action/locator IDs, target counts, durations, outcomes, and correlation IDs; do not log sensitive request bodies or credentials. Verify existing security logging separately before relying on it for production.

## 6. Phased implementation checklist

Typed transport implementation and its focused verification are complete, alongside the Phase 3 discovery route-registration item. Current Synapse test coverage is recorded above; the acceptance gaps remain open below. Changes span independent repositories; keep work and dependency versions coordinated without committing submodule pointers prematurely.

### Phase 0 — Freeze contracts and baseline fixtures

**Dependencies:** None. **Exit:** Decisions and executable test cases agreed before transport implementation.

- [ ] Capture direct Aviator ESRI, WFS, Weaver, and generic JSON fixtures using mocked upstream responses.
- [ ] Complete the input roundtrip spike from section 4, first proving actual Aviator schema/codec reconstruction before coding; record evidence and minimal required changes. Final-result serializer SPIKE is complete, but end-to-end plugin result serialization/output contract remains open.
- [ ] Choose canonical routes, action identity, collision handling, eligibility matrix, missing-plugin policy, output shapes, and failure/deadline semantics.
- [ ] Define plugin ownership and safe public metadata projection, including receiving-plugin installation versus automatic activation/configuration.
- [ ] Inspect shared JVM dependency closure and choose server composition location without app/Room/UI dependencies.

### Phase 1 — Generated definition and receiving-plugin contract

**Dependencies:** Phase 0. **Likely repositories:** Synapse and Aviator; Shared only if its codec contract needs an additive change.

- [ ] Update `CatalystGenerator` / `ServiceBundle` to emit canonical, collision-checked path/method/action definitions and input/output schemas. Canonical action path generation and per-action selector identity are implemented; remaining generator/schema and broader collision checks are open.
- [ ] Project public options safely; retain needed client deserialization metadata while leaving upstream execution materializations/configuration server-side.
- [ ] Ensure SynapsePlugin is available in the receiving composition, then automatically configured from materialization. Test absent installation, inactive/default configuration, and `serviceFilter` interactions explicitly.
- [x] Implement v2 typed JSON parameter transport with legacy string-list compatibility and serializer-aware cardinality; AreaAssist boundary/query helpers attach serializers. Decoder-to-route integration, schema validation, and action binding remain open.
- [ ] Prove original provider plugins cannot run client-side on gateway definitions, including already-installed defaults whose execution is not controlled solely by OAS materialization.
- [ ] Add ambiguity/collision tests before replacing `associate` behavior. Cover multiple methods on one path, multiple routes per service, duplicate locators, and encoded selectors.

### Phase 2 — Authorized original-action resolver and server composition

**Dependencies:** Phase 0 contracts; may proceed alongside Phase 1 after contract freeze.

- [x] Introduce a transport-independent resolver/executor boundary, injected through Koin conventions.
- [x] Implement exact original-action resolution by service ID, HTTP method, and original path from the caller-visible snapshot; reject inaccessible, ambiguous, disabled services and Synapse-materialized wrapper routes, with non-disclosing not-found handling.
- [x] Integrate caller-visible DB snapshot creation, locator candidate resolution, and basic fanout into execution. Boundedness and complete output contract remain open.
- [x] Snapshot target metadata in a short transaction and release it before provider execution. A dedicated transaction-boundary test remains open.
- [ ] Add only required `explicitOnly` persistence/schema changes with `V<Version>__<Description>.sql` migration and roundtrip tests; do not broaden registry migration scope.
- [x] Compose trusted installed Aviator plugins/adapters, serializers/codecs, and WFS CRS dependencies in the server deployment layer. Apply original route materializations and fail closed for missing or duplicate provider registrations.
- [ ] Define service/client cache lifecycle and reauthorization/invalidation behavior, or start uncached if correctness is clearer.

### Phase 3 — Single-action HTTP execution

**Dependencies:** Phases 1–2; typed input and output spikes must pass.

- [x] Decode the gateway request, resolve and authorize its action, and reconstruct parameters using registered codecs. Broader schema validation/action binding remains open.
- [x] Execute the original action through Aviator's existing pipeline; Catalyst does not issue direct provider calls.
- [x] Integrate final post-plugin serialization through Aviator's serializer contract and map current error statuses. Null-result-as-success semantics remain unresolved and end-to-end output behavior lacks integration coverage.
- [x] Wire Catalyst discovery in `config/Routes.kt` behind `SynapseConfig.catalyst.enabled`, preserving the existing discovery behavior.
- [x] Implement `POST /catalyst/{selector}` for locator candidate selection and basic fanout. Route integration tests remain open; bounded concurrency/deadlines and complete output/failure contract remain open.
- [x] Apply configurable request-byte limits, per-target response-byte limits, aggregate-response-byte limit, deadlines and cancellation. Outbound URLs are restricted to HTTPS and exact deployment allowlist hosts/IPs.
- [ ] Verify redirects, DNS rebinding/private-address treatment, redacted observability, credential isolation, and upstream authentication policy end-to-end.
- [x] Demonstrate automatic generated-definition invocation: integration coverage verifies installed-plugin activation, generated POST, typed v1 envelope, and decoded JSON response. Generic JSON direct-vs-gateway parity is covered by `CatalystExecutorIntegrationTest.generic JSON output matches direct Aviator execution`.
- [ ] Complete direct-versus-gateway parity with mocked provider transport for AreaAssist Boundary/Query; Koin codec wire roundtrips do not prove provider execution parity.

### Phase 4 — Locator-group fanout and collection

**Dependencies:** Verified single-action execution and agreed group contract.

- [x] Resolve all eligible caller-visible locator candidates, exclude Catalyst wrappers, enforce at most one source route per locator per service, and apply optional visible identity selection or ordered filters.
- [x] Query selected actions serially in service ID order through Aviator, outside the DB transaction; flatten list/JsonArray outputs and continue after per-target failures.
- [x] Add bounded concurrency, target cap, and per-target/overall deadlines. Finalize/document single/multi-target shapes and null/partial-failure payload/status semantics remains open.
- [ ] Complete fanout testing: target cap, concurrency/order/flattening, deadlines and cancellation are covered; mixed statuses/shapes, zero matches, duplicate locator routes, and remaining identity/filter edge cases remain to verify.

### Phase 5 — Hardening and documented release

**Dependencies:** Phases 1–4 acceptance suite.

- [ ] Verify missing-plugin behavior, malformed requests, outbound policy including redirect/DNS/private-network cases, credential isolation, cross-scope access, and concurrent request-state isolation.
- [ ] Reconcile `synapse/README.md`, API/OpenAPI, `.ai/architecture.md`, `.ai/business-rules.md`, and examples with actual implemented routes; distinguish available HTTP functionality from planned MCP.
- [ ] Record supported plugin composition and its deployment configuration, without secrets. Coordinate independent Aviator/Shared/Synapse artifact versions and standalone/composite build verification.
- [ ] Supply reproducible automated test commands/results and remaining limitations before changing this plan's verification status.

### Phase 6 — Separate future MCP milestone

**Dependencies:** Stable authorized HTTP executor and action catalog; separate design/acceptance review.

- [ ] Expose eligible actions from **all routes and methods declared by Synapse**, not only AreaAssist query/parcel actions. Apply `mcpEnabled`, scopes, action identity, and existing eligibility policies.
- [ ] Derive MCP tool/action schemas from declared contracts and map invocation to the same resolver/executor used by HTTP. No MCP-only provider execution or normalization logic.
- [ ] Choose the applicable MCP transport/version and SSE/streaming behavior separately; AA-114's SSE-ready intent is not proof of an implemented endpoint or a fixed transport choice.
- [ ] Specify authentication/session isolation, cancellation/deadlines, method side effects, input validation, and result/error mapping. Contract-test parity with HTTP execution.

## 7. Test and acceptance matrix

Use shared `kotlin.test`/coroutine tests for codecs and plugin behavior; Synapse JVM unit and server integration tests for routes/orchestration. Add test dependencies explicitly where missing. Mock upstream Aviator transport or use its mock converter/client as appropriate; do not replace the pipeline with a fake normalized result in parity tests. Use isolated DB integration fixtures for persistence/authorization where needed.

| Area | Required evidence |
|------|-------------------|
| Definition generation | Exact generated action-path uniqueness via stable action selectors; canonical route/method/parameters; OpenAPI 200 array and 400/404/502/503/504 responses; broader production schema/client generation, duplicate-overwrite handling, all intended grouped actions retained, and no private configuration leakage. |
| Client automation | Generated definition configures the installed Synapse plugin and generated POST executes with typed v1 request/decoded JSON response; missing/filtered installation behaves by explicit policy; no provider transforms execute twice. |
| Input fidelity | Boolean; structured Boundary/CRS; `List<Query>` and each supported variant; repeated strings; absent/null; non-null JSON body; and legacy string clients reconstruct correctly or fail explicitly under the versioned schema-aware envelope. |
| Plugin execution | ESRI/WFS request adaptation and Weaver/domain response stages actually run in order on the server; final typed parcel list is serialized and decoded by the client. |
| Direct/gateway parity | Same original definition, agnostic invocation, plugins, and mocked upstream response yield equivalent final outputs; provider request URLs/method/body match expectations. |
| Genericity | Non-GIS JSON object/list/scalar actions and non-GET provider methods work without AreaAssist-specific code in Catalyst. |
| Authorization | Public/anonymous, authenticated scoped, wrong scope, guessed ID, expired/invalid principal, hidden group member, and updated permissions all enforce the same policy. |
| Eligibility | Native/Catalyst/preferred/explicit-only/MCP combinations, persistence roundtrip, and missing required plugins match the documented matrix. |
| Group contract | Locator candidates, filter/identity precedence, serial service-ID order, list flattening, partial failures, empty/null output, and timeout behavior match published schemas. |
| Safety/lifecycle | No bearer forwarding/private option leak; recursive wrapper rejection; outbound policy; cancellation; concurrency limits; no held DB transaction during transport; shutdown and request-state isolation. |

### Initial HTTP milestone is accepted only when

- [x] Generated Synapse/Catalyst definitions drive client calls automatically using available installed plugins (covered by generated-client integration test); production-wide codegen coverage remains open.
- [ ] Single and locator-group execution use authorized original actions and the complete Aviator pipeline; locator fanout's boundedness and output/failure contract are explicit and tested.
- [ ] Typed AreaAssist input and final normalized output roundtrip with direct-execution parity; generic actions work without a custom normalizer.
- [ ] Action selection, collisions, response shapes, partial failure, status/null, deadlines, and unknown-plugin behavior are explicit and tested rather than implicit assumptions.
- [ ] Routes, generated OpenAPI, examples, and current-state documentation agree; MCP remains marked future until independently implemented.
- [ ] Focused tests and appropriate build checks actually succeed with recorded commands/toolchain; source inspection alone is not verification.

## 8. Verification workflow for future implementation

Implementation verification so far includes passing Synapse `:core:test`, Weaver grammar generation (`:weaver:tokenizer:generateKotlinGrammarSource`), and Synapse core compilation (`:synapse:core:compileKotlin`); this is not end-to-end verification. For future verification, follow root `AGENTS.md`: use the root mise environment pinned to **Temurin 25**, verify the runtime, and always pass `--no-daemon`. Do not substitute Java 21 or relax the pin to bypass failures.

From the monorepo root in PowerShell:

```powershell
mise install
mise exec -- java -version
# Continue only when Java reports 25.
mise exec -- .\gradlew.bat --no-daemon projects
```

Root `settings.gradle.kts` includes Synapse/Aviator/Shared as composite builds; `synapse/settings.gradle.kts` declares `:core` and `:host`. Inspect actual task listings (`tasks --all` for the relevant included project) before selecting focused test/build commands. Do not guess KMP test task names or claim known success. Record working directory, exact task path, Java version, test reports, and failures for each repository changed. For standalone verification, preserve the root mise environment and inspect standalone task names separately. Follow the root bounded-verification/orphaned-JVM precautions if commands hang.

Older module docs advertising JDK 17+ or Gradle invocations without `--no-daemon` do not override the root workflow. Their broader cleanup is outside this local plan task.

## 9. Open decisions and recommended sequence

| Decision | Resolve before | Recommended next step (not a settled API) |
|----------|----------------|------------------------------------------|
| Canonical discovery/execution paths and OpenAPI query modeling | Phase 1 | Compare generated and README contracts against actual Aviator matching; freeze one tested contract. |
| Unique action selector and locator membership | Phases 1–2 | Canonical service-ID plus method/original-path selector and exact generated action-path uniqueness are implemented. Locator membership/grouping semantics remain open and must preserve legitimate grouping explicitly. |
| Typed wire representation and backwards compatibility | Phases 1, 3 | Selected: versioned schema-aware typed JSON values, reconstructed from original OpenAPI schemas with Aviator serializers/codecs, while retaining legacy string decoding. Before coding, prove this actual Aviator reconstruction in the spike; use narrowly scoped codec metadata only if schemas prove insufficient. |
| Final-result serializer/type contract | Phase 3 | Serializer SPIKE established optional Aviator `resultSerializer` with fallback to the original output serializer and verified typed-list/null behavior; still exercise plugin outputs through the execution route and settle output/status contract. |
| Single/group output shape, flattening/keying, status/null and partial failures | Phases 1, 4 | Derive from caller contracts and parity tests; avoid a new envelope that breaks typed consumers. |
| Required versus optional plugins and absent-plugin response | Phases 1–2 | Prefer explicit failure for missing required behavior; agree discovery visibility/error details rather than silent downgrade. |
| Public metadata and plugin ownership representation | Phase 1 | Establish explicit projection/composition rules; test installed default plugins as well as copied OAS materialization. |
| `explicitOnly` persistence and group exclusion semantics | Phase 2 | Define eligibility first, then add only the necessary migration/mapping. |
| Shared plugin deployment/composition and artifact versions | Phase 2 | Verify JVM dependency closure and CRS initialization; keep generic gateway core independent of domain modules. |
| Operational limits, outbound policy, failure/cancellation semantics | Phases 3–4 | Choose configurable deployment limits and security policy; test deterministic behavior at boundaries. |
| MCP transport/tool mapping | Phase 6 only | Reuse the stable all-route/method catalog and executor; evaluate current MCP requirements at that milestone. |

Recommended order: contract/codec/output spikes -> generated definition and client automation -> authorized single-action execution -> bounded locator fanout -> hardening and release documentation -> separate MCP milestone. Do not implement aggregation or MCP on top of unproven typed input/output semantics.

## 10. Deployment plugin composition — researched finding and recommendation

**Status:** The contribution contract, Koin host-module loading, executor integration, and AreaAssist ESRI/WFS composition are implemented. Generic JSON direct-vs-gateway parity test exists and passed. AreaAssist-specific full Boundary/Query parity remains unverified/open.

### Verified composition findings

- `synapse/core/.../config/Frameworks.kt` installs Koin with a module binding `SynapseConfig` only. It does not load Synapse's `DI` or host plugin modules.
- `synapse/host/.../Application.kt` calls `configureFrameworks`, then security, HTTP, and routes; it has no explicit DI module registration. Standalone Synapse therefore needs an explicit, tested startup bootstrap for its own DI.
- `areaassist/server/.../Application.kt` explicitly installs Koin with the config module, Synapse `DI`, and `CodexDI`. This is an existing deployment composition root, but does not establish that the standalone Synapse host loads those modules.
- `synapse/core/.../di/AppModule.kt` contains the annotated `AppModule` and a manually declared `DI`; Codex loads the manual `DI`. Koin 4.2.2 is pinned. Koin's Ktor integration uses `install(Koin) { modules(...) }`; annotation-generated modules must be explicitly loaded. Koin can aggregate multibindings via `List<T>`/`getAll<T>()`. AreaAssist code already uses `@Single(binds = [...])`.
- Synapse core depends on Aviator's Ktor client and JSON/XML adapters, Weaver, Synapse, and OTel, but must remain generic. AreaAssist server already depends on `areaassist/shared`, the plugin artifacts, and Synapse core; this is the appropriate deployment layer for AreaAssist-specific composition. WFS is constructed as `WfsAdapterPlugin(registry: CrsRegistry)`, not a singleton, so its contribution must obtain/own the registry through host DI.
- `KtorAviatorServiceConverter.build` crystallizes the supplied plugin activation lambda and invokes `pluginsForRoute`. That selection includes every installed instance with an empty `serviceFilter`, or a locator match; route OAS materializations reconfigure only matching identities already installed. Missing installations are silently ignored. Thus installing every available provider into every service is unsafe; missing an explicitly materialized required identity must instead fail closed.
- `AviatorPluginInstance.requestReconfigure` defaults to returning `this`; Synapse returns a copy, while `WVPInstance` owns mutable `engine` initialized in `WeaverPlugin.preExecution`. Create fresh plugin instances per execution/action; never share mutable configured plugin instances across requests. An app-owned `HttpClient`/converter engine can be reused, with explicit application-shutdown cleanup.
- AreaAssist Shared currently has ESRI/WFS final parcel-list serializers and typed `Boundary`/`Query` codecs. These are contribution-provided codecs, not dependencies that belong in generic Synapse core.

### Implemented composition and execution

The generic `CatalystPluginContribution` contract declares provider identity, optional codecs, and a factory that creates a **fresh** Aviator instance. `configureFrameworks(vararg hostModules)` loads Synapse DI and supplied host Koin modules. AreaAssist server supplies ESRI/WFS contributions backed by `CrsRegistry`; its Koin test roundtrips Boundary, Query list (including field/content assertions), and Boolean through registered codecs. This validates contribution codec wiring, not full provider parity. Generic Synapse core remains independent of AreaAssist models.

For each selected action, the executor uses only provider identities materialized on the resolved original route, fails closed when a required contribution is missing or duplicated, decodes v1/v2 request values with registered codecs, executes via `KtorAviatorServiceConverter` and Aviator, then serializes the final result. Locator fanout runs in service-ID order, flattens list outputs, and continues after target errors. The shared `HttpClient` closes on `ApplicationStopped`; AreaAssist server also closes the executor at shutdown. Current error statuses, partial-failure limits, and unresolved null behavior are recorded above.

### Actionable implementation and test checklist

- [x] Add runtime test proving Synapse DI and supplied host modules load and contributions aggregate through Koin (execution result still to be recorded).
- [ ] Unknown explicitly materialized plugin identity fails closed; no silent Aviator omission.
- [ ] Route-specific activation test proves only declared providers (plus documented always-on contributions) activate; concurrent actions cannot leak plugin instances/configuration.
- [ ] Weaver test proves a fresh mutable Weaver plugin instance is created for every invocation.
- [x] Add AreaAssist Koin runtime composition test and generic direct-vs-gateway parity test using real pipeline scope. Generic JSON parity passed; AreaAssist full Boundary/Query parity remains open.
- [ ] Application shutdown closes the owned Aviator `HttpClient`/converter resources.
- [x] Expanded opt-in `DatabaseTransactionBoundaryIntegrationTest` exercises `catalystEndpoint`: the resolver callback uses a real DatabaseService R2DBC transaction and the execution callback opens a second transaction, asserting distinct PostgreSQL `txid_current()` IDs. It compiles and is included in the full test suite; it skips when `CATALYST_TEST_DATABASE_URL` is unset. An earlier, simpler version passed against disposable PostgreSQL. The endpoint-level test passed against disposable PostgreSQL 17 with actual DatabaseService resolution/execution callback transactions and distinct `txid_current()` IDs. The full Synapse core suite passed with `CATALYST_TEST_DATABASE_URL` set.

Locator candidate resolution and fanout are implemented; do not describe locator fanout as wholly deferred. Apache HttpClient 5 connection-time DNS pinning rechecks the exact allowlist/public-address policy and supplies vetted addresses to the socket resolver; redirects are disabled. This application-layer defense is implemented and tested. Host/network egress ACLs remain defense-in-depth operational verification; do not claim deployment firewall controls are in place. The endpoint-level `DatabaseTransactionBoundaryIntegrationTest` passed against disposable PostgreSQL 17: the real DatabaseService resolution and execution callbacks each used a transaction and reported different `txid_current()` IDs. The full `mise exec -- .\\gradlew.bat --no-daemon :synapse:core:test` suite passed with `CATALYST_TEST_DATABASE_URL` set; the AreaAssist Server test suite passed; `git diff --check` is clean; the temporary PostgreSQL container was stopped. Acceptance remains INCOMPLETE only pending deployment egress ACL verification and documented schema constructs beyond the supported validator. MCP remains separate. External Notary/YouTrack AA-114 synchronization is complete.
