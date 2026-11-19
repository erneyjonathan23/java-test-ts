# Submission

> Complete every section. Keep it short — this is the reviewers' entry point.

## Candidate

| Field | Value |
|---|---|
| Candidate ID (provided by your recruiter) | erneyjonathan@gmail.com |
| Final commit SHA | <!-- SHA of the last commit, the same one you send to the recruiter --> |
| Date and time of submission (with time zone) | <!-- e.g. 2026-11-21 18:00 UTC−5 --> |

## Time spent

| Part | Approximate time |
|---|---|
| A. Integration and data design | ≈ 20 min |
| B. Integration component | ≈ 1 h 10 min (first version, then hexagonal refactor, Chile, precedence rule, lease) |
| C. Azure IaC and CI/CD | ≈ 10 min |
| D. Operations | ≈ 5 min |
| E. Code review | ≈ 5 min |
| F. Responsible AI | ≈ 5 min |
| **Total** | **≈ 1 h 55 min** |

Elapsed working time on 2026-11-19 with Claude Code generating the drafts under my direction, matching the commit history; see [`AI_USAGE.md`](AI_USAGE.md). Time spent afterwards reviewing and studying the deliverables is not included.

## Stack

- Practical part (Part B): Java 21 (Temurin 21.0.13) + Spring Boot 3.5.16 + JUnit 5 + ArchUnit, Maven 3.10.1 wrapper; hexagonal architecture enforced by ArchUnit, 129 tests, 100% of mutants killed (PIT) in domain and provider adapters. Justified in [ADR-0002](docs/adr/0002-stack-and-data-store.md).
- Infrastructure as code: Bicep (CLI 0.49.2), validated with `az bicep lint/build/build-params`; not deployed.
- Pipeline: GitHub Actions — [`ci.yml`](.github/workflows/ci.yml), [`cd.yml`](.github/workflows/cd.yml), [`deploy-env.yml`](.github/workflows/deploy-env.yml).
- CI run link (optional): <!-- link to the first CI run after pushing, if available -->

## Where to start reading

1. [`docs/design/SOLUTION_DESIGN.md`](docs/design/SOLUTION_DESIGN.md): the end-to-end design. Sections 5 (idempotency and ordering) and 7 (capacity against the Salesforce budget) carry the key decisions.
2. [`practical/README.md`](practical/README.md) (architecture and what the tests prove), then the adapters in `practical/src/main/java/.../adapter/in/provider/` and [`WebhookIntakeTest`](practical/src/test/java/com/meridian/payments/intake/adapter/in/web/WebhookIntakeTest.java): what the tests prove (signatures with the providers' vectors, exactly-once publication, no loss, no personal data in logs).
3. [`docs/operations/INC-0412-RESPONSE.md`](docs/operations/INC-0412-RESPONSE.md) and [`pr-review/REVIEW.md`](pr-review/REVIEW.md).

## Known gaps and what I would do next

In priority order:

1. **Real adapters for the ports** (Service Bus publisher with a 3 s timeout, Azure SQL idempotency store with Flyway migrations, Key Vault secret provider with caching). Today they are stubs with the same semantics (`practical/README.md`).
2. **The recorder and the Salesforce sync** are designed (design §5–7, ADR-0001/0003); their ordering rule (`PaymentStatePrecedence`) and the database schema (`db/migrations`) are already implemented and tested. Next would be the recorder's optimistic-concurrency update and the composite-upsert error classification, test-first.
3. **Trace propagation**: OpenTelemetry context through Service Bus into the recorder (described in `OPERATIONS.md`; intake metrics are already implemented).
4. **Spring Boot 4 / Jackson 3** upgrade as a separate change.
5. **CI**: the workflows have not run yet on GitHub. PSRule rules may need suppressions with justification after the first run.
6. **Open questions** for the business and the Salesforce team: partial refunds (A-9), normalizing 9-digit references (A-8), and the 99.5%-in-1-hour SLO vs Salesforce outages longer than 60 minutes (A-12).
