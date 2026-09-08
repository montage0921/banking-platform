# Quickstart Validation: Multi-Step Agent Responses via MCP + RAG (GIC Rate Inquiries)

## Prerequisites

- Backend dependencies installed and backend build passes.
- Postgres chatbot datasource available for chat_interaction_log and vector store.
- Knowledge-base folder includes existing six files plus 07-gics-explained.md.
- MCP rates server module available at voltio-rates-mcp-server/ (see its own
  mvnw/mvnw.cmd - it runs and is packaged independently of backend/).

## Start Services

1. Start the rates MCP server: from voltio-rates-mcp-server/, run
   `./mvnw spring-boot:run` (or `mvnw.cmd spring-boot:run` on Windows). Listens on
   port 8081 by default; override with `MCP_RATES_SERVER_URL` on the backend side if
   it's hosted elsewhere.
2. Start backend service.
3. Start frontend service (optional for API-only validation).

Note: Backend must still start even when the MCP server is stopped (or step 1 is
skipped entirely) - spring.ai.mcp.client.initialized=false in application.properties
makes the connection lazy, and McpClientDiagnostics catches any connection failure at
its ApplicationReadyEvent check rather than letting it fail startup.

## Validation Scenarios

### Scenario A: Multi-step combined answer

- Input question:
  - What is the current one-year GIC rate, and is a GIC a good option for my emergency fund?
- Expected outcome:
  - Single response includes one-year rate value from MCP tool.
  - Same response includes educational points from RAG article (locked-in term, guaranteed return tradeoff).
  - No extra API round trip and no response contract change.

### Scenario B: Tools-used logging

- Trigger a turn that invokes both getGicRates and knowledge-base search.
- Query chat_interaction_log latest row for that customer. Use the seeded `salaried` persona (`seed.salaried@voltio.test`), which has enough history for the chatbot to personalise rather than fall back; resolve its customer id from the login rather than hardcoding one. See `backend/src/main/resources/personas/voltio-personas.yaml`.
- Expected outcome:
  - tools_used is non-empty and includes both tool names.
  - sources behavior remains intact.

### Scenario C: Tool-less turn logging

- Input greeting:
  - Hello there.
- Expected outcome:
  - Normal response.
  - tools_used is null or empty in the persisted turn row.

### Scenario D: MCP server down resilience

- Stop rates MCP server while backend stays running.
- Ask GIC-rate question again.
- Expected outcome:
  - Chat request still returns response (not HTTP 500).
  - Response gracefully indicates current rates could not be retrieved now.

### Scenario E: Incremental knowledge ingestion

- Seed environment with only files 01-06 already ingested.
- Add 07-gics-explained.md and restart backend.
- Expected outcome:
  - 07 file is newly ingested.
  - Existing file vectors are not duplicated.

### Scenario F: Module naming/documentation consistency

- Review renamed MCP module pom name/description and config comments.
- Expected outcome:
  - No text claims module is disposable-only test scaffolding.
  - Naming consistently reflects production-relevant rates tool usage.

## Constitution-focused checks

- Contract-before-code: verify no public chat DTO changes.
- Error semantics: verify UI still relies on centralized error mapping.
- Environment parity: verify MCP host remains config-driven and not hardcoded in code.
- Theme parity: if any chat UI copy/states changed, verify in Classic and Neon themes.
