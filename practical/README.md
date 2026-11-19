# Practical part — Payment notification intake (MX, CO)

`payments-intake`: receives the webhooks of **Quetzal Pagos (Mexico)** and **Andina Pay (Colombia)**, verifies them exactly as each provider specifies, translates them into the canonical event and publishes it, answering `2xx` only once the event is durably published. It implements the *intake* stage of [`SOLUTION_DESIGN.md`](../docs/design/SOLUTION_DESIGN.md); writing to Salesforce is out of scope.

## Stack and versions

| Item | Version |
|---|---|
| Java | 21 (tested with Eclipse Temurin 21.0.13) |
| Spring Boot | 3.3.5 (web, actuator) |
| Build | Maven 3.9.9 through the Maven Wrapper (`mvnw`) |
| Tests | JUnit 5, AssertJ, Mockito, Spring MockMvc, networknt json-schema-validator 1.5.2 |

Why Java instead of Mule: see [ADR-0002](../docs/adr/0002-stack-and-data-store.md).

## Build and run the tests

```bash
cd practical
./mvnw test          # Windows: mvnw.cmd test
```

The tests read the provider samples **byte for byte** from `../reference/providers/` and the event schema from `../docs/api/`, so they must run from a checkout of the whole repository. The repository's `.gitattributes` keeps the sample files from being converted to CRLF on Windows; any change to line endings would break the HMAC test vectors, which is exactly what a real provider signature would do.

Run locally (stubs only, nothing external is called):

```bash
INTAKE_SECRETS_QUETZAL_WEBHOOK=qz_test_whsec_4f8a1c2e9b7d \
INTAKE_SECRETS_ANDINA_WEBHOOK=andina_test_secret_7c3e19ab \
./mvnw spring-boot:run
```

(These are the providers' published **sandbox** secrets from `reference/`. The vectors are signed for 2026-11-28 onward, so live requests with them are rejected as stale unless re-signed; the tests use a controllable clock instead.)

## Project structure

```
src/main/java/com/meridian/payments/intake
├── IntakeService.java          shared flow: verify → map → claim → publish → acknowledge
├── web/                        HTTP: WebhookController (raw bytes), CorrelationIdFilter, ProblemHandler (status mapping)
├── provider/
│   ├── ProviderAdapter.java    the only provider-specific extension point (NFR9)
│   ├── quetzal/QuetzalAdapter  t=…,v1=… hex HMAC, 300 s window, rotation, minor units, idempotency key = event id
│   ├── andina/AndinaAdapter    Base64 HMAC over "<timestamp>.<body>", 10 min window, idempotency key = transaction + state
│   └── support/                Hmac (constant time), Amounts (ISO exponents), PolicyReferenceNormalizer, JsonFields, SecretProvider
├── canonical/                  CloudEvents envelope + canonical payload (no personal data)
├── ports/                      EventPublisher, IdempotencyStore (interfaces of external dependencies)
└── stub/                       in-memory / environment implementations of the ports
```

Adding a provider = one new `ProviderAdapter` bean + its secret; `IntakeService`, the controller and the contracts do not change.

## Behaviour (what the tests prove)

| Behaviour | Requirement | Tests |
|---|---|---|
| All published test vectors verify; one changed byte, a re-serialized body, a moved/re-formatted timestamp, the wrong secret, missing or malformed headers are rejected (`401`) | FR1, NFR6 | `QuetzalAdapterTest.Signature`, `AndinaAdapterTest.Signature` |
| Replay windows enforced at the exact boundaries (300 s / 10 min); the signed timestamp cannot be swapped | FR1 | same |
| Secret rotation: any of two `v1` signatures (Quetzal), and current + next secret on our side | FR1, NFR6 | `acceptsTwoSignatures…`, `acceptsTheNextSecret…` |
| Mapping of every sample, amounts without floating point (minor → major units), UTC state-change time, policy reference normalization (INC-0412 cases), unknown fields ignored, unknown states **rejected** rather than defaulted | FR2 | `*AdapterTest.Mapping`, `SupportTest` |
| Every produced event validates against `premium-payment.schema.json` and contains no payer data | FR2, NFR6 | `CanonicalEventContractTest` |
| Andina retry with a new `notificacion_id` and Quetzal re-deliveries are acknowledged but published **once**; every attempt is recorded for operations | FR4, FR7 | `WebhookIntakeTest` |
| Broker or store unavailable → `503` (not accepted); the provider's retry then publishes; a failure after publishing still answers `200` | NFR3 | `brokerOutage…`, `idempotencyStoreOutage…`, `failureToMarkAsPublished…` |
| Out-of-order notifications are all accepted with their own `statusChangedAt` (ordering is resolved downstream, ADR-0003) | FR5 (input to) | `outOfOrderNotifications…` |
| Oversized, malformed, unknown provider; unsafe correlation ids replaced (log injection) | NFR6 | `WebhookIntakeTest` |
| No personal data in the application logs, including rejected requests | NFR6 | `personalDataNeverReachesTheLogs` |

Response time (NFR4): the flow does no remote call other than the idempotency claim and the publish; the production publisher has a 3 s timeout, below Andina's 5 s and Quetzal's 10 s.

## What is stubbed and how

| Dependency | Port | Stub | Production implementation (not built) |
|---|---|---|---|
| Azure Service Bus topic | `EventPublisher` | `InMemoryEventPublisher` | `ServiceBusSenderClient` with `DefaultAzureCredential`, `messageId = idempotencyKey`, 3 s timeout |
| Azure SQL (`inbound_event`, `inbound_attempt`) | `IdempotencyStore` | `InMemoryIdempotencyStore` | JDBC with Entra ID token auth, `INSERT` + PK violation handling |
| Azure Key Vault | `SecretProvider` | `EnvironmentSecretProvider` (env vars) | `SecretClient` with managed identity, 5-minute cache, list of current + next secret |
| Clock | `java.time.Clock` | `MutableClock` in tests | `Clock.systemUTC()` |

The stubs are wired by `StubConfiguration` only when no real bean exists, and log a `WARN` at startup so a stubbed deployment cannot go unnoticed.

## Known gaps

- Real adapters for Service Bus, Azure SQL (with Flyway migrations) and Key Vault are not implemented; only their contracts and semantics (exercised through the stubs).
- Two concurrent first deliveries of the same business event can both publish (the stub, like a plain SQL claim, does not lease the key). Harmless because the recorder is idempotent; a short `IN_FLIGHT` lease would remove it.
- The validation of the API Manager's machine-to-machine token (A-4) is assumed to be done by platform configuration and is not in the code.
- Chile (Austral) is designed but not implemented; it would be a third adapter (hex HMAC of the raw body, no timestamp, `eventId` as idempotency key, CLP exponent 0).
- Metrics (Micrometer counters per provider/outcome and latency histograms for freshness) and OpenTelemetry trace propagation into Service Bus are described in `OPERATIONS.md`, not wired.
- No load test; capacity figures in the design are calculations.
