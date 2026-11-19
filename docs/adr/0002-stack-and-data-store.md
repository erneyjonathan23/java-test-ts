# ADR-0002: Implementation stack and data store

- **Status:** Accepted
- **Date:** 2026-11-19

## Context

We need (a) a runtime for the intake, recorder and Salesforce sync, and (b) a store for operational state: idempotency keys, payment state for ordering, and per-notification status for operations (FR7).

Forces:

- Webhook signatures are computed over the **raw bytes** of the body (and a timestamp); constant-time comparison; several secrets during rotation (FR1, NFR6).
- Response before the providers' timeouts (Andina 5 s) and no loss (NFR3, NFR4).
- Managed identities and Key Vault (NFR6); cost awareness (NFR8); new providers without touching the shared flow (NFR9).
- Every API exposed to providers must be published through the API Manager, which can front services running on Azure.
- Reviewers will read, not run, the code; MuleSoft Enterprise dependencies (MUnit, connectors) need repository credentials.
- The squad already has a Java 21 / Spring Boot prototype on Azure SQL (PR-214).

## Options considered — runtime

### Option A1 — MuleSoft (Mule 4 + DataWeave) on the integration platform
- Pros: reference stack of the company; DataWeave is excellent for payload mapping; native Salesforce connector; API Manager policies out of the box.
- Cons: per-vCore licensing makes always-on workers expensive for ~1.4 msg/s average load; raw-byte HMAC and constant-time comparison need custom Java modules anyway; managed identities to Azure Service Bus/SQL/Key Vault are not native to a runtime outside Azure; CI needs Enterprise repository credentials.

### Option A2 — Java 21 + Spring Boot 3 on Azure Container Apps
- Pros: full control of raw bytes and crypto (`javax.crypto`, `MessageDigest.isEqual`); first-class Azure SDKs with `DefaultAzureCredential` (managed identity); KEDA autoscaling on Service Bus backlog; JUnit 5 tests with no proprietary dependencies; matches the squad's prototype skills; Container Apps scale-to-few replicas keeps cost low.
- Cons: a second runtime next to Mule for the platform team to operate; mapping code is more verbose than DataWeave; we must build the resilience patterns (resilience4j) ourselves.

### Option A3 — Azure Functions (Java, Flex Consumption)
- Pros: cheapest at low volume; Service Bus trigger built in.
- Cons: cold starts threaten Andina's 5 s timeout unless always-ready instances are paid for; harder to run a long-lived leader-elected sync loop; less control over HTTP raw body handling.

## Options considered — data store

### Option S1 — Azure SQL Database (General Purpose, serverless)
- Pros: unique constraints give idempotency for free; conditional `UPDATE … WHERE row_version = @v` gives ordering with optimistic concurrency; operations queries by any identifier with plain indexes; Entra ID authentication with managed identity; point-in-time restore; the team already uses it.
- Cons: a relational schema to migrate (Flyway); vertical scaling; serverless auto-pause would add latency (disabled in prod).

### Option S2 — Azure Cosmos DB for NoSQL (serverless or autoscale)
- Pros: elastic, global, cheap at this volume, TTL-based retention.
- Cons: uniqueness only within a partition; lookups by provider event id, transaction id and correlation id are cross-partition queries or need extra containers kept consistent by us; transactional batch limited to one partition key; RU modeling adds design risk without a volume that needs it.

### Option S3 — Azure Table Storage / Redis
- Pros: very cheap / very fast.
- Cons: no secondary indexes (Table) or no durability guarantees suitable for idempotency (Redis as primary store). Rejected.

## Decision

- **Runtime: Java 21 + Spring Boot 3.5 on Azure Container Apps**, three deployables: `payments-intake`, `payments-recorder` (includes the leader-elected Salesforce sync) and `payments-ops`. All three are published or reached through the API Manager, so the "no point-to-point" rule holds. The code follows a hexagonal (ports and adapters) structure enforced by ArchUnit: framework-free domain and use case, provider logic as one `ProviderAdapter` implementation per provider, discovered by Spring; adding a country = new adapter + secret + route, no change to the shared flow (NFR9).
- **Store: Azure SQL Database**, General Purpose serverless (min 0.5, max 4 vCores, auto-pause disabled in prod), Entra-only authentication, private endpoint, zone redundancy in prod.

MuleSoft remains the right tool for the API Manager layer and for other squads' system APIs; if the company mandates Mule for runtime logic, the adapters' contract (verify → map → idempotency key) ports one-to-one to Mule flows with a Java module for HMAC.

## Consequences

- Positive: cryptography and idempotency are explicit and unit-tested with the providers' test vectors; the whole flow uses managed identities; cost is a few hundred USD a month (see `AZURE_SERVICES.md`); new providers are isolated.
- Negative: diverges from the company's reference stack, so the squad must own Java runtime patching, base images and dependency scanning; the platform team operates one more runtime; we write mapping code instead of DataWeave.
- Risks and mitigation:
  - *Skills / ownership* → container images built from a hardened base, Dependabot + CodeQL in CI, runbooks in `OPERATIONS.md`.
  - *SQL as a dependency of the intake* → the intake answers `503` when SQL is down (provider retries, nothing accepted is lost); zone-redundant SQL in prod; the claim is a single indexed insert.
  - *Schema evolution* → Flyway migrations, backward-compatible (expand/contract).
