# Tasks: Postgres as the Primary Application Database (H2 Removal)

**Input**: Design documents from `/specs/004-postgres-primary-database/`

**Prerequisites**: plan.md (required), spec.md (required)

**Tests**: Tests are required because this feature changes where every entity persists, replaces schema generation with versioned migrations, and moves the test suite onto a different database engine.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing.

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Establish the baseline that later phases are verified against, and confirm the assumptions the plan rests on.

- [ ] T001 Confirm current H2 configuration touchpoints (lines 4-20) in backend/src/main/resources/application.properties
- [ ] T002 Confirm the two-datasource split and why the primary must stay explicit in backend/src/main/java/com/group1/banking/config/PrimaryDataSourceConfig.java and backend/src/main/java/com/group1/banking/config/ChatbotDataSourceConfig.java
- [ ] T003 [P] Record pre-migration behaviour of the seven feature areas (Chatbot, Agent, Tools, RAG, Insights, OPS, Risk) as the comparison target for Phase 6
- [ ] T004 [P] Confirm no flyway_schema_history table exists and that only V003 has ever been executed (via spring.sql.init), establishing that the migration baseline can be defined freely

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Stand up the Postgres database, add Flyway, author the baseline schema and re-point the primary datasource. Nothing else can be verified until the application boots against Postgres.

**CRITICAL**: No user story work begins until this phase is complete.

- [X] T005 [P] Add banking_core creation script in docker/postgres-init/01-create-banking-core.sql
- [X] T006 Mount the init script into the existing pgvector service in docker-compose.yml
- [ ] T007 Document database creation in SETUP.md: `docker compose down -v` then `up -d` re-initialises the volume and runs the init script (losing only the re-ingestible knowledge base and chat log); the manual `CREATE DATABASE banking_core;` is the alternative for keeping existing volume data
- [X] T008 Add flyway-core and flyway-database-postgresql dependencies in backend/pom.xml
- [X] T009 Author the baseline schema from the 16 @Entity classes under com.group1.banking.entity (Hibernate schema export needs a running build, which was unavailable; ddl-auto=validate audits the result on first boot and names any mismatch)
- [X] T010 Review the generated DDL and commit it as backend/src/main/resources/db/migration/V001__baseline_schema.sql
- [X] T011 Delete the three superseded H2-dialect scripts backend/src/main/resources/db/migration/V001__create_savings_goals.sql, V002__account_control_freeze_unfreeze.sql and V003__create_audit_log.sql
- [X] T012 Replace the H2 datasource block with the Postgres datasource block in backend/src/main/resources/application.properties
- [X] T013 Set spring.jpa.hibernate.ddl-auto=validate, enable Flyway, and remove spring.sql.init.mode and spring.sql.init.schema-locations in backend/src/main/resources/application.properties
- [X] T014 [P] Add APP_DB_URL, APP_DB_USERNAME and APP_DB_PASSWORD alongside the existing CHATBOT_DB_* entries in .env.example and .env
- [X] T015 [P] Update the class javadoc from "H2/MySQL database" to Postgres in backend/src/main/java/com/group1/banking/config/PrimaryDataSourceConfig.java

- [X] T015a Replace the MySQL-only columnDefinition = "LONGBLOB" with the dialect default (bytea) in backend/src/main/java/com/group1/banking/entity/ExportCacheEntity.java
- [X] T015b Replace the bare @Lob on a String, which Hibernate maps to a PostgreSQL large object (oid) rather than text, with columnDefinition = "TEXT" in backend/src/main/java/com/group1/banking/entity/IdempotencyRecord.java

**Checkpoint**: The backend boots against banking_core with a Flyway-migrated schema that passes validation.

---

## Phase 3: User Story 1 - Application Runs on Postgres and Data Survives Restart (Priority: P1) MVP

**Goal**: Every JPA repository resolves against Postgres, and data written through the API is still there after a restart.

**Independent Test**: Start the backend against a running Postgres, write a record through an endpoint, restart, and read the same record back.

### Tests for User Story 1

- [ ] T016 [P] [US1] Add a startup assertion that the primary datasource reports a PostgreSQL product and driver in backend/src/test/java/com/group1/banking/config/PrimaryDataSourceConfigTest.java

### Implementation for User Story 1

- [X] T017 [US1] Start the backend against banking_core and confirm zero startup errors with no H2 file present on disk
- [ ] T018 [US1] Verify write-restart-read persistence through a real endpoint (create an account, restart, read it back)
- [ ] T019 [US1] Verify the chatbot's pgvector datasource, ChatInteractionLogRepository.ensureSchema() and KnowledgeBaseIngestionRunner still resolve against banking_chat and are unaffected
- [ ] T020 [US1] Verify the application fails fast with an error naming the database connection when the primary Postgres is unreachable at startup, rather than starting in a partially-usable state

**Checkpoint**: The application is running on Postgres with durable data.

---

## Phase 4: User Story 2 - Schema Is Versioned and Applied Identically Everywhere (Priority: P1)

**Goal**: Schema comes from ordered migrations, and any mapping drift fails at startup instead of at query time.

**Independent Test**: Point the application at an empty database, confirm migrations build the full schema, restart and confirm nothing re-applies.

### Tests for User Story 2

- [ ] T021 [P] [US2] Add a migration test asserting the full schema is created against an empty database in backend/src/test/java/com/group1/banking/migration/FlywayMigrationTest.java
- [ ] T022 [P] [US2] Add a second-boot test asserting no migration is re-applied and startup succeeds in backend/src/test/java/com/group1/banking/migration/FlywayMigrationTest.java
- [ ] T023 [P] [US2] Add a validation test asserting startup fails with a named table or column when an entity mapping does not match the migrated schema in backend/src/test/java/com/group1/banking/migration/SchemaValidationTest.java

### Implementation for User Story 2

- [X] T024 [US2] Run the application against a freshly migrated database and close any ddl-auto=validate gaps by correcting V001__baseline_schema.sql (one gap in ~150 columns: risk_scores.calculated_at - Boot's CamelCaseToUnderscoresNamingStrategy rewrites the explicit @Column(name = "calculatedAt") to snake_case. Also required before this passed: the spring-boot-flyway auto-configuration module, without which Flyway sits on the classpath and never runs)
- [ ] T025 [US2] Document the drop-and-recreate path for developers holding a pre-migration database in SETUP.md

**Checkpoint**: Schema is reproducible from migrations alone and drift is caught at boot.

---

## Phase 5: User Story 3 - Seed Personas Load Into Postgres and Are Reproducible (Priority: P1)

**Goal**: The demo seed loads cleanly into Postgres, produces the documented persona spread, and does not collide with application-generated inserts.

**Independent Test**: Load the seed into a freshly migrated database, verify the documented row counts and persona distribution, then unseed, reseed and confirm the same state.

### Tests for User Story 3

- [ ] T026 [P] [US3] Verify seed row counts (100 customers, 235 accounts, 26,170 transactions, 89 savings goals) and the four-band persona distribution against a freshly seeded database
- [ ] T027 [P] [US3] Verify an application-generated insert succeeds after seeding without a primary-key collision, for customers, accounts and savings goals
- [ ] T028 [P] [US3] Verify unseed followed by reseed produces equivalent data with no duplicate or orphaned rows

### Implementation for User Story 3

- [ ] T029 [US3] Update SQL emission to be Postgres-compatible in backend/scripts/generate_seed.py
- [ ] T030 [US3] Regenerate backend/scripts/seed_demo_data.sql at the existing RNG seed and confirm it loads without error
- [X] T031 [US3] Add the identity-sequence repair script in backend/scripts/reset_sequences.sql
- [ ] T032 [US3] Verify backend/scripts/unseed_demo_data.sql removes exactly the seeded rows on Postgres
- [ ] T033 [US3] Document the seed, sequence-reset and unseed procedure in SETUP.md

**Checkpoint**: A QA scenario run twice from the same seeded state produces the same result.

---

## Phase 6: User Story 4 - Every Feature Area Behaves Normally on Postgres (Priority: P2)

**Goal**: All seven named feature areas read and write correctly against Postgres, matching the Phase 1 baseline.

**Independent Test**: With a migrated and seeded database, run one representative read and one representative write per feature area.

### Implementation for User Story 4

- [ ] T034 [P] [US4] Verify Chatbot: a chat turn returns a response, writes chat_interaction_log on banking_chat and audit_log on banking_core
- [ ] T035 [P] [US4] Verify Agent: a transfer proposal and its confirmation through /api/chat/confirmations/{token} persist the pending action, the transfer and both audit rows
- [ ] T036 [P] [US4] Verify Tools: each in-process @Tool method returns data from Postgres for a seeded customer
- [ ] T037 [P] [US4] Verify RAG: knowledge-base retrieval and citations are unchanged, confirming the chatbot datasource is untouched
- [ ] T038 [P] [US4] Verify Insights: spending insights for customers in each persona band match the Phase 1 baseline
- [ ] T039 [P] [US4] Verify OPS: freeze and unfreeze write account_control_audit, return both events from the control-history endpoint, and record ACCOUNT_FROZEN/ACCOUNT_UNFROZEN in audit_log
- [ ] T040 [P] [US4] Verify Risk: risk scores for customers in each persona band match the Phase 1 baseline
- [ ] T041 [US4] Verify every audit write across all seven areas succeeds against the Postgres audit_log with no missing-column error
- [ ] T042 [US4] Verify a database constraint violation still returns the same HTTP status and error code as before, in backend/src/main/java/com/group1/banking/exception/GlobalExceptionHandler.java

**Checkpoint**: No feature area behaves differently on Postgres than it did on H2.

---

## Phase 7: User Story 5 - Repository and Integration Tests Run Against Real Postgres (Priority: P2)

**Goal**: The test suite validates against the same engine production runs, with no embedded database anywhere.

**Independent Test**: Run the backend test suite with no H2 artifact on the classpath and confirm the repository and integration tests pass.

### Tests for User Story 5

- [ ] T043 [US5] Add test-scope spring-boot-testcontainers, testcontainers junit-jupiter and testcontainers postgresql dependencies in backend/pom.xml
- [ ] T044 [US5] Add the shared container base class in backend/src/test/java/com/group1/banking/support/PostgresTestSupport.java

### Implementation for User Story 5

- [ ] T045 [US5] Re-point the eight core repository tests (AccountRepositoryTest, CustomerRepositoryTest, UserRepositoryTest, TransactionRepositoryTest, TransactionQueryRepositoryTest, AuditLogRepositoryTest, ExportCacheRepositoryTest, IdempotencyRecordRepositoryTest) to PostgresTestSupport, removing their H2 @TestPropertySource blocks, in backend/src/test/java/com/group1/banking/repository/
- [ ] T046 [US5] Re-point the five feature repository tests (GicRepositoryTest, StandingOrderRepositoryTest, NotificationDecisionRepositoryTest, NotificationPreferenceRepositoryTest, PendingAgentActionRepositoryTest) the same way, in backend/src/test/java/com/group1/banking/repository/
- [ ] T047 [US5] Re-point backend/src/test/java/com/group1/banking/integration/AuthCustomerIntegrationTest.java to PostgresTestSupport
- [ ] T048 [US5] Run the full backend suite twice consecutively and confirm the second run is unaffected by data from the first

**Checkpoint**: The suite is green on Postgres and needs no manual database cleanup between runs.

---

## Phase 8: User Story 6 - H2 Is Gone From the Project (Priority: P3)

**Goal**: No H2 dependency, property, console route, documentation reference or database file remains.

**Independent Test**: Search the repository for H2 dependencies, properties, console references and database files and confirm none remain.

### Implementation for User Story 6

- [ ] T049 [US6] Remove the com.h2database:h2 dependency and both spring-boot-h2console declarations (one runtime-scoped, one with no scope) in backend/pom.xml
- [ ] T050 [US6] Confirm no spring.h2.* property, H2 JDBC URL, driver or credential remains in backend/src/main/resources/application.properties
- [ ] T051 [US6] Untrack backend/data/digitalbankdb.mv.db, data/digitalbankdb.mv.db and data/digitalbankdb.trace.db from git and remove both data/ directories from the working tree
- [ ] T052 [US6] Confirm the existing .gitignore rules for data/, backend/data/ and *.mv.db are now effective, in .gitignore
- [ ] T053 [US6] Run a repository-wide search for h2, .mv.db, .trace.db and h2-console and confirm zero remaining results

**Checkpoint**: H2 is fully removed from the build, configuration, working tree and git history going forward.

---

## Phase 9: Polish and Documentation

- [ ] T054 [P] Replace the H2 console instructions with Postgres access instructions (psql and pgAdmin, host localhost port 5433) in SETUP.md
- [ ] T055 [P] Remove the now-obsolete H2 schema-drift and two-data-folders gotchas in SETUP.md
- [ ] T056 [P] Document that running the backend test suite now requires a working Docker daemon in SETUP.md
- [ ] T057 Verify all nine success criteria (SC-001 through SC-009) from specs/004-postgres-primary-database/spec.md

---

## Dependencies & Execution Order

- **Phase 1** has no dependencies and can start immediately.
- **Phase 2** blocks everything else; no user story can be verified before the application boots against Postgres.
- **Phase 3 (US1)** is the MVP and must pass before Phases 4-6 mean anything.
- **Phase 4 (US2)** and **Phase 5 (US3)** both depend on Phase 2 but not on each other.
- **Phase 6 (US4)** depends on Phase 5, since the feature-area checks read seeded persona data.
- **Phase 7 (US5)** depends only on Phase 2 (it needs the migrations) and can run in parallel with Phases 4-6.
- **Phase 8 (US6)** must come last among implementation phases: removing H2 before the Postgres path works would leave no working database at all.
- **Phase 9** follows Phase 8.

## Parallel Execution Notes

- Tasks marked **[P]** touch different files and can be worked simultaneously within their phase.
- T034-T040 are seven independent feature-area verifications and parallelise well across reviewers.
- T045 and T046 are the same mechanical edit applied to thirteen files and are deliberately batched into two tasks rather than thirteen.
- T009 through T011 are strictly sequential: the baseline must be generated, reviewed and committed before the superseded scripts are deleted.
