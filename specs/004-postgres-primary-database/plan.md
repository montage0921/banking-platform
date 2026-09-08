# Implementation Plan: Postgres as the Primary Application Database (H2 Removal)

**Branch**: `004-postgres-primary-database` | **Date**: 2026-09-03 | **Spec**: specs/004-postgres-primary-database/spec.md

**Input**: Feature specification from `/specs/004-postgres-primary-database/spec.md`

> **For agentic workers**: use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task by task. Tasks live in `specs/004-postgres-primary-database/tasks.md` and use checkbox (`- [ ]`) syntax for tracking. Read `spec.md` alongside this plan - the plan argues from the spec, and the spec is the binding authority where the two appear to disagree.

## Summary

Move every JPA-mapped table off the file-backed H2 database and onto a PostgreSQL database hosted by the Postgres server the chatbot already runs. Schema stops being derived by `spring.jpa.hibernate.ddl-auto=update` and becomes an ordered set of Flyway migrations validated at startup. Repository and integration tests move from embedded H2 to a Testcontainers Postgres, which is what allows H2 to leave the build entirely. No entity, repository method, service behavior or API contract changes.

## Technical Context

**Language/Version**: Java 21 (Spring Boot 4.1.0), JavaScript/React (Vite) - frontend untouched by this feature

**Primary Dependencies**: Spring Data JPA / Hibernate, Flyway (`flyway-core` + `flyway-database-postgresql`, new), PostgreSQL JDBC driver (already present at runtime scope for the chatbot datasource), Testcontainers (`junit-jupiter` + `postgresql` + `spring-boot-testcontainers`, new, test scope)

**Storage**: Primary datasource moves from `jdbc:h2:file:./data/digitalbankdb` to a `banking_core` PostgreSQL database on the existing `banking-pgvector` server (port 5433). The chatbot's `banking_chat` database, its pgvector knowledge-base table and `chat_interaction_log` are unchanged and keep their own datasource.

**Testing**: JUnit 5, Mockito, Spring Boot test slices. Thirteen `@DataJpaTest` repository classes plus `AuthCustomerIntegrationTest` re-point from embedded H2 to a shared Testcontainers Postgres via `@ServiceConnection`. Service and controller tests that mock repositories are unaffected.

**Target Platform**: Local developer machines and QA/demo hosts running the backend against a Postgres reachable by JDBC URL

**Project Type**: Full-stack web application; this feature touches backend configuration, migrations, tests and operational docs only

**Performance Goals**: No regression in application startup time beyond migration execution on first boot; no change to request latency. Test-suite runtime is expected to increase because tests now use a real database container.

**Constraints**:
- No feature logic, entity mapping, repository method or API contract may change (spec FR-015)
- The chatbot datasource, `ChatInteractionLogRepository.ensureSchema()` and `KnowledgeBaseIngestionRunner` must remain untouched (spec FR-016)
- `PrimaryDataSourceConfig` must keep declaring the `@Primary` `DataSource` explicitly; deleting it silently re-points all JPA at the chatbot database
- Cloud provisioning for deployed QA/demo environments is out of scope (spec OCR-003)

**Scale/Scope**:
- ~20 JPA entities across core banking, savings goals, GICs, risk scoring, notifications, standing orders, account control and audit
- Demo seed of 100 customers, 235 accounts, 26,170 transactions, 89 savings goals (9.8 MB generated SQL)
- 14 test classes re-pointed; 2 build files, 1 properties file, 1 compose file, 2 docs updated

## Global Constraints

Copied verbatim from the spec; every task's requirements implicitly include these.

- Primary datasource connects to PostgreSQL, configured through environment variables with local defaults (FR-001)
- Core banking data lives in its own database on the existing `banking-pgvector` server, separate from `banking_chat` (FR-002)
- `PrimaryDataSourceConfig` continues to declare the `@Primary` `DataSource` bean explicitly (FR-003)
- `spring.jpa.hibernate.ddl-auto=validate` (FR-006)
- `spring.sql.init.mode` and `spring.sql.init.schema-locations` are removed (FR-007)
- No entity, repository method, service behavior or API contract changes (FR-015)
- Chatbot datasource and its startup bootstrap are unchanged (FR-016)
- Every REST contract is unchanged; constraint violations keep their current HTTP status and error code (CER-001, CER-002)

## Constitution Check (Pre-Design Gate)

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- [x] API contract impacts identified (none; this is an infrastructure change with no contract surface - CER-001).
- [x] Contract-affecting changes include same-PR updates for mappings, tests and consumers (no contract changes; entity mappings unchanged, tests re-pointed in the same change).
- [x] Error semantics remain standardized (CER-002 requires constraint violations to keep their current status/code despite Postgres reporting different SQLState values than H2).
- [x] Security/CORS implications reviewed (no new endpoint; the `/h2-console` route and its `web-allow-others=true` setting are removed, which reduces exposed surface).
- [x] Environment parity confirmed - this is the feature's whole purpose: one database engine across dev, QA, demo and tests, configured entirely by environment variable.
- [x] Layer ownership preserved (no service or UI change; the change is confined to configuration, migrations and test harness).
- [x] Testability gate defined (startup + persistence across restart, migration idempotency, mapping-validation failure, seed load and reproducibility, per-feature-area smoke, test suite on Postgres).
- [x] No silent degradation risk accepted without traceability - `ddl-auto=validate` converts today's silent schema drift into a startup failure that names the offending table or column (CER-004).

## Phase 0: Research Output

Decisions taken before design, with the alternatives that were rejected.

**Database topology - one server, two databases.** Core banking data gets a `banking_core` database on the existing `banking-pgvector` container rather than a second container or a shared database with two schemas. Keeps the current two-datasource code structure exactly as-is and adds no service to run. Rejected: separate container (more onboarding steps, second volume); shared database with two schemas (couples chatbot and banking data lifecycles).

**Schema management - Flyway now, not deferred.** The Definition of Done's repeatability criterion is not satisfiable while schema is derived from `ddl-auto=update`, and that mechanism is the direct cause of the `audit_log` missing-column failures already seen in this codebase. `ddl-auto` moves to `validate` rather than being removed, so mapping drift fails at startup instead of at query time. Rejected: keeping `ddl-auto=update` on Postgres (carries the same failure mode across).

**Migration baseline - one new baseline file, superseding the three existing scripts.** `V001__create_savings_goals.sql`, `V002__account_control_freeze_unfreeze.sql` and `V003__create_audit_log.sql` were never applied by Flyway (Flyway is not a dependency; only `V003` ever ran, through `spring.sql.init`). No `flyway_schema_history` exists anywhere, so there is no history to preserve and the baseline can be defined freely. The three files are H2/MySQL dialect (`BIGINT AUTO_INCREMENT`, `CLOB`) and cover only three of roughly twenty tables, so keeping them and adding a fourth migration for everything else would produce a misleading split. They are replaced by a single `V001__baseline_schema.sql` holding the complete Postgres schema. Rejected: rewriting all three and adding `V004` for the remainder.

**Baseline authoring - generated from Hibernate, then reviewed by hand.** The baseline DDL is produced by running Hibernate's schema export against the PostgreSQL dialect and then reviewed, rather than hand-written from the entity classes, so no column is missed. The generated output is treated as a draft, not as the committed artifact.

**Test database - Testcontainers.** Repository and integration tests get a real Postgres. This is what makes "H2 fully removed" literally true and closes the dialect gap between tests and production. Accepted cost: Docker becomes a hard prerequisite for running the backend test suite. Rejected: keeping H2 at test scope (leaves H2 in the build and keeps tests validating a different dialect); pointing tests at the developer's local Postgres (shared mutable state, no CI story).

**First-run gotcha - the existing compose volume.** Postgres only executes `/docker-entrypoint-initdb.d/` scripts when initialising an empty data directory. Every developer already has a populated `pgvector-data` volume, so an init script alone will not create `banking_core` for them. The documented path is a one-line `CREATE DATABASE` against the running container; `docker compose down -v` also works but discards the ingested knowledge base, which then has to be re-ingested on next boot.

## Phase 1: Design Output

**Compose change** - add the init script to the existing `pgvector` service so fresh environments get both databases:

```yaml
    volumes:
      - pgvector-data:/var/lib/postgresql/data
      - ./docker/postgres-init:/docker-entrypoint-initdb.d:ro
```

with `docker/postgres-init/01-create-banking-core.sql`:

```sql
CREATE DATABASE banking_core;
```

The database is owned by the existing `${CHATBOT_DB_USERNAME:-banking_chat}` superuser role; no second role is created. Existing environments run the same statement manually:

```
docker exec -it banking-pgvector psql -U banking_chat -d postgres -c "CREATE DATABASE banking_core;"
```

**Primary datasource configuration** - `backend/src/main/resources/application.properties`, replacing the current H2 block:

```properties
spring.datasource.url=${APP_DB_URL:jdbc:postgresql://localhost:5433/banking_core}
spring.datasource.username=${APP_DB_USERNAME:banking_chat}
spring.datasource.password=${APP_DB_PASSWORD:banking_chat}
spring.datasource.driver-class-name=org.postgresql.Driver

spring.jpa.hibernate.ddl-auto=validate
spring.flyway.enabled=true
spring.flyway.locations=classpath:db/migration
```

Removed in the same edit: `spring.sql.init.mode`, `spring.sql.init.schema-locations`, `spring.h2.console.enabled`, `spring.h2.console.path`, `spring.h2.console.settings.web-allow-others`, and the commented-out in-memory H2 URL. `.env.example` and `.env` gain `APP_DB_URL`, `APP_DB_USERNAME`, `APP_DB_PASSWORD` alongside the existing `CHATBOT_DB_*` entries.

**Build changes** - `backend/pom.xml`: add `org.flywaydb:flyway-core` and `org.flywaydb:flyway-database-postgresql` (Flyway 10+ requires the database-specific module; Spring Boot's BOM manages both versions). Remove the `com.h2database:h2` dependency and *both* `spring-boot-h2console` declarations - one at runtime scope and one with no scope declared. Add test-scope `org.springframework.boot:spring-boot-testcontainers`, `org.testcontainers:junit-jupiter`, `org.testcontainers:postgresql`. The existing `org.postgresql:postgresql` runtime dependency already covers the primary datasource.

**Baseline migration authoring** - generate the draft by running the backend once with schema export enabled against Postgres:

```properties
spring.jpa.properties.jakarta.persistence.schema-generation.scripts.action=create
spring.jpa.properties.jakarta.persistence.schema-generation.scripts.create-target=target/schema-baseline.sql
```

Review the output, then commit it as `backend/src/main/resources/db/migration/V001__baseline_schema.sql`. Delete the three superseded scripts. The properties above are temporary scaffolding and are not committed.

**Seed sequence repair** - the seed inserts explicit primary keys, which does not advance Postgres identity sequences. `backend/scripts/reset_sequences.sql` (new), run after seeding:

```sql
DO $$
DECLARE r RECORD;
BEGIN
  FOR r IN
    SELECT c.table_name, c.column_name, pg_get_serial_sequence(c.table_name, c.column_name) AS seq
    FROM information_schema.columns c
    WHERE c.table_schema = 'public'
      AND pg_get_serial_sequence(c.table_name, c.column_name) IS NOT NULL
  LOOP
    EXECUTE format(
      'SELECT setval(%L, COALESCE((SELECT MAX(%I) FROM %I), 0) + 1, false)',
      r.seq, r.column_name, r.table_name);
  END LOOP;
END $$;
```

**Test harness** - `backend/src/test/java/com/group1/banking/support/PostgresTestSupport.java` (new), a base class the repository and integration tests extend:

```java
@Testcontainers
public abstract class PostgresTestSupport {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16");
}
```

The container is `static`, so one instance is shared across every test class that extends it rather than one per class. Each `@DataJpaTest` class then drops its `@TestPropertySource` block (currently `spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1` and `spring.jpa.hibernate.ddl-auto=create-drop`) and adds `@AutoConfigureTestDatabase(replace = Replace.NONE)` so Boot does not substitute an embedded database. `@DataJpaTest` remains transactional and rolls back per test, which satisfies the isolation requirement in spec User Story 5, Scenario 2. Flyway runs against the container, so tests exercise the same migrated schema as production.

## Project Structure

### Documentation (this feature)

```
specs/004-postgres-primary-database/
├── spec.md              # Feature specification (complete)
├── plan.md              # This file
└── tasks.md             # Task breakdown
```

### Source Code (repository root)

```
docker/postgres-init/
└── 01-create-banking-core.sql          # NEW - creates banking_core on fresh volumes

docker-compose.yml                      # MODIFIED - mount init script into pgvector service

backend/pom.xml                         # MODIFIED - +flyway, +testcontainers, -h2, -h2console x2

backend/src/main/resources/
├── application.properties              # MODIFIED - Postgres datasource, validate, flyway; H2 + sql.init removed
└── db/migration/
    ├── V001__baseline_schema.sql       # NEW - complete Postgres schema
    ├── V001__create_savings_goals.sql          # DELETED - superseded, H2 dialect
    ├── V002__account_control_freeze_unfreeze.sql # DELETED - superseded, H2 dialect
    └── V003__create_audit_log.sql              # DELETED - superseded, H2 dialect

backend/src/main/java/com/group1/banking/config/
└── PrimaryDataSourceConfig.java        # MODIFIED - javadoc only ("H2/MySQL" -> Postgres); no code change

backend/scripts/
├── generate_seed.py                    # MODIFIED - emit Postgres-compatible SQL
├── seed_demo_data.sql                  # REGENERATED
└── reset_sequences.sql                 # NEW - advance identity sequences past seeded PKs

backend/src/test/java/com/group1/banking/
├── support/PostgresTestSupport.java    # NEW - shared Testcontainers base class
├── config/PrimaryDataSourceConfigTest.java      # NEW - asserts the primary datasource is PostgreSQL
├── migration/FlywayMigrationTest.java           # NEW - empty-database migration + second-boot no-op
├── migration/SchemaValidationTest.java          # NEW - mapping mismatch fails startup
├── repository/*Test.java               # MODIFIED x13 - extend base, drop H2 @TestPropertySource
└── integration/AuthCustomerIntegrationTest.java # MODIFIED - same

.env / .env.example                     # MODIFIED - APP_DB_* alongside CHATBOT_DB_*
.gitignore                              # VERIFIED - existing data/, backend/data/, *.mv.db rules suffice
SETUP.md                                # MODIFIED - Postgres prerequisites, seeding, psql/pgAdmin access
```

**Structure Decision**: No new module, package or architectural layer. The two-datasource structure (`PrimaryDataSourceConfig` + `ChatbotDataSourceConfig`) is preserved exactly; only the primary's target engine changes. Migrations live in the existing `db/migration` folder that was already named for Flyway.

## Constitution Check (Post-Design Re-check)

- [x] Contract Before Code - no contract changes; `CER-001` holds by construction since no controller, DTO or mapping is touched.
- [x] Single Source of Truth for Error Semantics - `GlobalExceptionHandler` remains the only mapping point; the design adds a verification task for `DataIntegrityViolationException` under Postgres rather than adding a second handler.
- [x] Security Is Default - the H2 console route and its `web-allow-others=true` setting are removed; database credentials move to environment variables with local-only defaults.
- [x] Environment Parity and Operability - one engine across dev, QA, demo and tests; all connection settings environment-overridable; startup fails fast when the database is unreachable or the schema does not match.
- [x] Business Logic in Services - untouched; no service or UI file is modified.
- [x] Testability Is a Merge Gate - every user story in the spec has a corresponding verification task, and the full suite must pass on Postgres with H2 absent.
- [x] No Silent Degradation - `validate` replaces `update`, converting silent drift into a named startup failure.
- [x] Style and Structure Discipline - no file grows materially; the only new production artifacts are one SQL migration and one SQL init script.
- [x] Theme Parity - not applicable; no frontend change.

## Complexity Tracking

| Item | Why it is necessary | Simpler alternative rejected because |
|------|--------------------|--------------------------------------|
| Flyway added in this ticket rather than later | The DoD requires the same scenario to produce the same result twice, which `ddl-auto=update` cannot guarantee | Porting the datasource alone carries the existing silent-drift failure mode onto Postgres |
| Testcontainers added | "H2 fully removed" is incompatible with 14 tests that depend on embedded H2 | Keeping H2 at test scope leaves H2 in the build and keeps tests on the wrong dialect |
| Baseline replaces three existing migration scripts | They are H2 dialect, cover 3 of ~20 tables, and were never Flyway-applied | Rewriting and keeping them produces a misleading split where `V004` holds most of the schema |
| Seed regeneration plus a sequence-reset script | Explicit-PK inserts leave Postgres identity sequences behind, so the first app insert collides | No alternative; this is inherent to seeding explicit keys on Postgres |
