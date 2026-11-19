# AI usage disclosure

> **Mandatory.** AI assistants are allowed. What we value is how you governed their use.
> If you did not use any AI tool, tick the box below and leave the rest empty.

- [ ] I did not use any AI tool in this assessment.


## Tools

| Tool (name and version, if known) | Where you used it (part and files) | What you used it for |
|---|---|---|
| Claude Code (Anthropic, model Claude Opus 5.5), desktop app | All parts: `docs/design/`, `docs/adr/`, `docs/api/`, `practical/`, `infra/`, `.github/`, `docs/operations/`, `pr-review/REVIEW.md`, `docs/ai/`, `docs/ASSUMPTIONS.md` | Reading and analysing the scenario and reference material; drafting the design, ADRs, contracts, code, tests, Bicep, pipelines, operations documents and the PR review; running the build, the tests and the Bicep validation locally. |

## Validation

What was verified by **executing** something, not by reading AI output:

- **Provider test vectors**: all 9 signatures in `reference/providers/*` were recomputed with an independent Python script (standard `hmac`/`hashlib`) before writing any Java. This revealed that the vectors only match with LF line endings: a Windows checkout converts the samples to CRLF and every signature fails. Fixed with `.gitattributes` (`reference/** -text`).
- **Part B**: `./mvnw test` runs 99 tests, all passing (Java 21.0.13, Spring Boot 3.5.16). They use the providers' samples and vectors byte for byte, and every canonical event produced from the samples is validated against `premium-payment.schema.json` by an independent JSON Schema validator (networknt). One test checks that no personal data from the samples appears in the application logs. The ArchUnit rules were checked to fail by temporarily adding a Spring annotation to the use case (then reverted).
- **Part C**: `az bicep lint` (0 warnings), `az bicep build` and `az bicep build-params` for dev/test/prod pass (Bicep 0.49.2). Nothing was deployed.
- Capacity numbers in the design were recomputed by hand from NFR1 and `API_CONSTRAINTS.md`.
- INC-0412 analysis was cross-checked against the incident data (e.g. 43,120 `400` responses = 4,312 dead-lettered messages × 10 deliveries; first DLQ message 41 minutes after Andina 2.3 took effect).
- PR-214 line numbers were computed from the diff with a script, not estimated.

## What I discarded or corrected

Concrete corrections made during the work, after a check failed or a closer reading of the material:

- **Service Bus Standard with private endpoints** (first draft of the design) — wrong: private endpoints are a Premium-only feature. Changed to Premium in prod and Standard in dev/test, and documented the cost trade-off (≈ $660/month) in `AZURE_SERVICES.md`.
- **Leader election with a blob lease** for the Salesforce sync — replaced with an Azure SQL application lock (`sp_getapplock`), which removes a storage account and its role assignments.
- **KEDA scale rule with managed identity** failed Bicep validation on API version `2024-03-01`; moved to `2025-01-01`, where the property exists.
- **JUnit `@FieldSource`** is not available in the JUnit version shipped with Spring Boot 3.3; replaced with `@MethodSource`.
- **Spring Boot 3.3.5** (first version used) is out of open-source support; upgraded to 3.5.16 after checking Maven Central. Spring Boot 4 was considered and deferred (Jackson 3 migration, no benefit for this deliverable).
- The first version was "ports and adapters" in spirit but the use case depended on Spring's `HttpHeaders` and `@Service`; restructured into explicit `domain` / `application` / `adapter` packages with framework-free inner layers and ArchUnit rules.
- An integration test used `austral` as the "unknown provider" example; it became a real provider and the test was changed.
- A generated test used `Set.of(...)` with duplicate elements and failed at runtime; corrected.
- Shell heredocs failed to write some Java files on Windows; files were rewritten with the editor tool and recompiled.
- Rejected a "default to `Pending` for unknown states" mapping (it is one of the PR-214 findings): unknown states are rejected with `422` and alerted.

## Data I shared with AI tools

- The public assessment repository (scenario, specifications, fictional sample payloads with fictional personal data, the providers' fictional sandbox secrets) and the recruiter's e-mail text.
- No real secrets, credentials, personal data or material from any employer were shared.

## My own review

<!-- Which files you reviewed line by line, what you changed after reviewing, what you would defend differently in the live session, and anything you disagree with. -->
