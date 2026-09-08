# Implementation Plan: Shared Seed Personas & Baseline Dataset

**Branch**: `004-shared-seed-personas` | **Date**: 2026-09-07 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/004-shared-seed-personas/spec.md`

## Summary

Define four named personas — salaried customer, goal saver, operations user, and a deliberately sparse newcomer — as a single YAML catalogue that is simultaneously the human reference and the source of expected values the tests assert against. A guarded startup component reads that catalogue and writes the personas through the existing repositories, anchoring every date relative to the moment of seeding so a persona's classification against the risk-scoring window never drifts. Seeding is off by default and refuses outright on a production-shaped configuration. Applying it twice fills only what is missing; restoring a known state is a separate, explicitly invoked reset that is scoped per persona so one person's cleanup cannot destroy another's in-flight work in a shared environment.

The technical crux is that two independently configured thresholds define "insufficient data" — risk scoring needs a transaction older than `minMonths: 3`, the chatbot needs at least `min-for-personalization: 3` spend transactions in a 30-day window — and one persona has to sit clear of *both*, in the same direction, without landing on either boundary.

## Technical Context

**Language/Version**: Java 21

**Primary Dependencies**: Spring Boot 4.1.0 (`spring-boot-starter-data-jpa`, `-security`, `-validation`), SnakeYAML via Spring's existing YAML property binding (already used for `risk-score-rules.yaml`). No new dependency required.

**Storage**: Primary datasource is H2 in file mode (`jdbc:h2:file:./data/digitalbankdb`) with `ddl-auto=update`; MySQL and PostgreSQL drivers are on the classpath for other environments. The chatbot's pgvector store is a separate datasource and is out of scope — personas carry no vector-store rows.

**Testing**: JUnit 5 + `spring-boot-starter-test`, Mockito, `spring-security-test`. 71 existing test classes; `@WithCustomUser` ([WithCustomUser.java](backend/src/test/java/com/group1/banking/controller/WithCustomUser.java)) is the established pattern for authenticated controller tests.

**Target Platform**: JVM on Linux (containerised; see [dockerfile](dockerfile) and [k8s/](k8s/))

**Project Type**: Web application — Spring Boot backend under [backend/](backend/), Vite/React frontend under [src/](src/)

**Performance Goals**: Seeding a clean environment completes within normal application startup, adding no more than a couple of seconds. Roughly 4 personas, 6 accounts, 2 goals and 60–90 transactions — small enough that bulk-insert tuning is unwarranted.

**Constraints**: No new HTTP endpoints and no DTO changes (keeps the constitution's contract and CORS gates untouched). Off by default in every environment. All dates relative, never absolute. No baseline version number, change log, or generated documentation — all three were explicitly ruled out during clarification.

**Scale/Scope**: 4 personas is the committed baseline, not a closed set; the catalogue must accept a fifth without code changes.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- [x] **API contract impacts identified**. None. This feature adds no controller, no DTO, and no field to an existing response. The `CER-*` requirement family in the spec template was dropped for exactly this reason. If a future ticket exposes seeding over HTTP, that ticket owns the contract review.
- [x] **Contract-affecting changes include same-PR updates** for backend mappings, controller tests, frontend consumers. Not applicable — no contract changes.
- [x] **Error semantics remain standardized**. Seed and reset failures surface as startup failures and test failures, never as HTTP responses, so they never reach `GlobalExceptionHandler` or the frontend's `axiosClient.js` mapping layer. No new error code is introduced.
- [x] **Security/CORS implications reviewed**. No new `SecurityConfig` matcher, because no new endpoint. CORS is untouched. The security surface that *is* added — seeded credentials, and an administrator-role persona — is covered by SCR-001 through SCR-004 and by the two-guard design in [research.md](./research.md).
- [x] **Environment parity confirmed**. The enabling flag and the production check are both environment-driven with a local-safe default of *disabled*. No hardcoded hostname, no cluster-internal DNS name. The same jar behaves correctly in local, CI, QA and production without a rebuild.
- [x] **Layer ownership preserved** — with one justified exception recorded in Complexity Tracking below: the seeder writes through repositories rather than the service layer.
- [x] **Testability gate defined**. No endpoint behaviour changes, so the success/auth/business-failure triad does not bind here. The replacement gate is stricter and is what this feature is actually for: catalogue validation runs wherever the four features' tests run (FR-020), plus guard tests covering both refusal paths and a reset-scope test.
- [x] **No silent degradation risk**. A refused seed is reported distinctly from a failed seed (SCR-002), and a partially applied baseline is a failure rather than a state (FR-024). Catalogue drift fails a test rather than sitting unnoticed.
- [x] **Theme parity (Principle IX)**. Not triggered — this feature adds no user-facing UI, no component, and no visual state. The personas are *consumed* by UI work in other features, which carry their own theme obligations.

**Gate result: PASS**, with one violation carried into Complexity Tracking.

## Project Structure

### Documentation (this feature)

```text
specs/004-shared-seed-personas/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── persona-catalogue.md   # Phase 1 output: the catalogue schema
├── checklists/
│   └── requirements.md  # From /speckit-specify + /speckit-clarify
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created here)
```

### Source Code (repository root)

```text
backend/src/main/resources/
└── personas/
    └── voltio-personas.yaml          # THE catalogue. Source of truth for both
                                      # humans and tests. Hand-edited, never generated.

backend/src/main/java/com/group1/banking/seed/
├── PersonaCatalogue.java             # Records binding voltio-personas.yaml
├── PersonaCatalogueProperties.java   # @ConfigurationProperties loader
├── SeedGuard.java                    # The two independent guards (SCR-001)
├── PersonaSeeder.java                # Fill-in-missing apply (FR-012)
├── PersonaResetService.java          # Per-persona restore (FR-013–FR-015)
├── PersonaDateAnchor.java            # Relative offsets → Instants (FR-010)
└── PersonaSeedRunner.java            # ApplicationRunner; guarded entry point

backend/src/test/java/com/group1/banking/seed/
├── PersonaCatalogueValidationTest.java  # Catalogue ⟷ seeded data (FR-019, FR-020)
├── SeedGuardTest.java                   # Both refusal paths (SCR-001, SC-011)
├── PersonaResetScopeTest.java           # Reset isolation (FR-014, SC-010)
├── PersonaDeterminismTest.java          # Same seed → same outcomes (FR-009)
├── SparsePersonaThresholdTest.java      # Both insufficient-data paths (FR-007)
└── Personas.java                        # Test-side accessor: the ONLY way tests
                                          # name a persona or read an expected value
```

**Structure Decision**: This is the existing web-application layout, and the feature lands entirely in the Spring Boot backend under [backend/](backend/). A new `seed` package sits alongside the existing `service`, `config` and `repository` packages rather than inside any of them, because seeding is neither request-scoped business logic nor configuration — it is a build-and-test facility with its own lifecycle. The catalogue lives in `src/main/resources` beside the existing [risk-score-rules.yaml](backend/src/main/resources/risk-score-rules.yaml), which establishes the precedent for hand-edited YAML that both the application and its tests read.

The frontend is untouched. No file under [src/](src/) changes.

## Complexity Tracking

> Filled because the Constitution Check above records one violation that needs justification.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| **Seeder writes through repositories, not the service layer** (tension with Principle V, "business invariants in backend services") | Service methods such as `AccountService.createAccount` resolve the acting user from `SecurityContextHolder` and enforce ownership rules that assume a live authenticated request. Driving them from an `ApplicationRunner` would mean fabricating a security context at startup — more machinery, and a authentication-shaped hole that exists only to serve test data. | Seeding through the service layer was rejected on that basis. The invariants are not abandoned: `PersonaCatalogueValidationTest` asserts the seeded rows satisfy the same conditions the services would have enforced (positive balances, account numbers matching `ACC%010d`, goal progress derivable from account balance), so a divergence between seeded state and service-produced state fails a test rather than passing silently. |
| **Seed code ships in `src/main`, not `src/test`** | QA and demo environments run the real, packaged application. Seed code confined to `src/test` would be unreachable there, which defeats three of the four user stories. | Test-scope-only seeding was rejected because it would leave QA and demos exactly where they are today. The exposure this creates is contained by SCR-001's two guards, which default to disabled and refuse production outright. |

## Phase Status

- [x] Phase 0 — research complete → [research.md](./research.md)
- [x] Phase 1 — design complete → [data-model.md](./data-model.md), [contracts/persona-catalogue.md](./contracts/persona-catalogue.md), [quickstart.md](./quickstart.md)
- [x] Constitution re-check after Phase 1 design: **PASS**, no new violations. The design added no endpoint, no DTO, and no error code; the two Complexity Tracking entries above are unchanged by it.
- [ ] Phase 2 — task breakdown (`/speckit-tasks`)
