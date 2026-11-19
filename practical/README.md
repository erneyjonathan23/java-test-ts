# Practical part — Payment notification intake

`payments-intake` receives the webhooks of **Quetzal Pagos (Mexico)** and **Andina Pay (Colombia)** — the scope of Part B — plus **Austral Pagos (Chile)**, added to show that a new provider is only a new adapter (NFR9). It verifies each notification exactly as its provider specifies, translates it into the canonical event and publishes it, answering `2xx` only once the event is durably published. It implements the *intake* stage of [`SOLUTION_DESIGN.md`](../docs/design/SOLUTION_DESIGN.md); writing to Salesforce is out of scope.

## Part B requirements at a glance

| Requirement | Where it is implemented | Proven by |
|---|---|---|
| FR1 — verify each provider as specified | `QuetzalAdapter`, `AndinaAdapter`, `AustralAdapter`, `support/Hmac` | `*AdapterTest.Signature` |
| FR2 — one canonical event | `adapter.toCanonical()`, `domain/PremiumPaymentEvent` | `*AdapterTest.Mapping`, `CanonicalEventContractTest` |
| FR4 — exactly once in business terms | `IntakeService` + `IdempotencyStore` (claim with a lease) | `WebhookIntakeTest`, `InMemoryIdempotencyStoreTest` |
| NFR3 — no accepted notification lost | `IntakeService`: `2xx` only after the event is published | `brokerOutage…`, `idempotencyStoreOutage…` |
| NFR4 — answer within the provider timeout | `TimeLimitedEventPublisher`, `IntakeProperties` | `aHangingBroker…`, `IntakePropertiesTest` |
| NFR6 — secrets and personal data | `SecretProvider` port, adapters never read payer fields | `personalDataNeverReachesTheLogs`, `CanonicalEventContractTest` |

The full list of behaviours and tests is in [Behaviour](#behaviour-what-the-tests-prove).

## Stack and versions

| Item | Version |
|---|---|
| Java | 21 (tested with Eclipse Temurin 21.0.13): records, pattern matching for `switch`, **virtual threads** |
| Spring Boot | 3.5.16 (web, actuator/Micrometer), structured JSON logs (ECS) |
| Build | Maven 3.9.9 through the Maven Wrapper (`mvnw`) |
| Tests | JUnit 5, AssertJ, Mockito, Spring MockMvc, **ArchUnit 1.4.2**, networknt json-schema-validator 1.5.9 |

Why Java instead of Mule: see [ADR-0002](../docs/adr/0002-stack-and-data-store.md). Spring Boot 3.5 keeps Jackson 2; moving to Boot 4 (Jackson 3) is a planned, separate upgrade.

## Build and run the tests

```bash
cd practical
./mvnw test          # Windows: mvnw.cmd test
```

**129 tests, all green.** Line coverage 93%, branch coverage 82% (JaCoCo report in `target/site/jacoco`). **Mutation testing (PIT) kills 100% of the 118 mutants** in the domain and the provider adapters, the code that decides money and authenticity: `./mvnw test-compile org.pitest:pitest-maven:mutationCoverage`. The tests read the provider samples **byte for byte** from `../reference/providers/` and the event schema from `../docs/api/`, so they must run from a checkout of the whole repository. The repository's `.gitattributes` keeps the sample files from being converted to CRLF on Windows: a change of line endings breaks the HMAC test vectors, exactly as it would break a real provider signature.

Run locally (stubs only, nothing external is called):

```bash
INTAKE_SECRETS_QUETZAL_WEBHOOK=qz_test_whsec_4f8a1c2e9b7d \
INTAKE_SECRETS_ANDINA_WEBHOOK=andina_test_secret_7c3e19ab \
INTAKE_SECRETS_AUSTRAL_WEBHOOK=aus_test_whsec_2b9d6f10 \
./mvnw spring-boot:run
```

These are the providers' published **sandbox** secrets from `reference/`. The Quetzal and Andina vectors are signed for 2026-11-28 onward, so live requests with them are rejected as stale unless re-signed; the tests use a controllable `Clock` instead.

## Architecture: hexagonal (ports and adapters)

```
com.meridian.payments.intake
├── domain/                     plain Java: canonical event (CloudEvents envelope), PaymentStatus,
│                               PaymentStatePrecedence (FR5 ordering rule), Money/Amounts (ISO exponents),
│                               PolicyReferenceNormalizer
├── application/                the use case, framework-free
│   ├── IntakeService           verify → map → claim idempotency key → publish → acknowledge
│   ├── RequestHeaders          case-insensitive headers without Spring types
│   ├── spi/ProviderAdapter     the only provider-specific extension point (anti-corruption layer)
│   └── port/out/               EventPublisher, IdempotencyStore, SecretProvider, IntakeMetrics
├── adapter/
│   ├── in/web/                 WebhookController (raw bytes), CorrelationIdFilter, RequestSizeLimitFilter, ProblemHandler
│   ├── in/provider/            quetzal/, andina/, austral/ + support/ (Hmac constant time, strict JsonFields)
│   └── out/                    messaging/TimeLimitedEventPublisher, metrics/MicrometerIntakeMetrics, stub/ (in-memory)
└── config/                     IntakeConfiguration (composition root) and IntakeProperties (typed, validated settings)
```

The rules are **enforced by `ArchitectureTest` (ArchUnit) on every build**: the domain depends only on the JDK; the application layer does not depend on Spring, Jakarta, Micrometer, Jackson or Azure; adapters are reached only from `config`; provider adapters do not depend on each other; inbound adapters do not know outbound ones. Breaking a rule fails the build. This was checked by temporarily adding `@Service` to `IntakeService`.

Adding a provider = one `ProviderAdapter` class + its secret. `IntakeService`, the controller and the event contract do not change: Austral was added exactly this way.

## Behaviour (what the tests prove)

| Behaviour | Requirement | Tests |
|---|---|---|
| All 9 published test vectors verify; one changed byte, a re-serialized body, a moved or re-formatted timestamp, another event's signature, the wrong secret, missing or malformed headers are rejected (`401`) | FR1, NFR6 | `*AdapterTest.Signature` |
| Replay windows enforced at the exact boundaries (300 s / 10 min); the signed timestamp cannot be swapped; Austral replays are neutralised by `eventId` idempotency | FR1 | same, `WebhookIntakeTest` |
| Secret rotation: any of two `v1` signatures (Quetzal), and current + next secret on our side | FR1, NFR6 | `acceptsTwoSignatures…`, `acceptsTheNextSecret…` |
| Mapping of every sample; amounts without floating point and never rounded (CLP has no decimals); UTC state-change time; policy reference normalization (INC-0412 cases); unknown fields ignored; unknown states **rejected** rather than defaulted | FR2 | `*AdapterTest.Mapping`, `DomainRulesTest` |
| Every produced event validates against `premium-payment.schema.json` and contains no payer data | FR2, NFR6 | `CanonicalEventContractTest` |
| Andina retry with a new `notificacion_id`, Quetzal and Austral re-deliveries: acknowledged but published **once**; every attempt recorded for operations | FR4, FR7 | `WebhookIntakeTest` |
| Broker or store unavailable → `503` (not accepted); the provider's retry then publishes; a failure after publishing still answers `200` | NFR3 | `brokerOutage…`, `idempotencyStoreOutage…`, `failureToMarkAsPublished…` |
| A **hanging** broker is answered with `503` in < 3 s (publish timeout on virtual threads), never with a provider-side timeout | NFR4 | `aHangingBroker…`, `TimeLimitedEventPublisherTest` |
| Out-of-order notifications are all accepted with their own `statusChangedAt` | FR5 (input to) | `outOfOrderNotifications…` |
| **Ordering rule** used downstream: the final state is the same for every arrival order (all permutations of the real samples end `REFUNDED`); a late capture never overrides a refund; partial refunds keep the largest amount | FR5 | `PaymentStatePrecedenceTest` |
| Concurrent deliveries of the same event: exactly one publishes (lease); the other gets `503` and its retry is acknowledged; a failed publication releases the lease | FR4, NFR3 | `concurrentDeliveries…`, `InMemoryIdempotencyStoreTest` |
| Metrics `intake.accepted{provider,country,outcome}`, `intake.rejected{…,reason}`, `intake.latency` | NFR7 | `metricsAreTaggedPerCountryAndOutcome` |
| Oversized (rejected before reading when `Content-Length` is declared), malformed, unknown provider; unsafe correlation ids replaced (log injection) | NFR6 | `WebhookIntakeTest` |
| No personal data in the application logs, including rejected requests | NFR6 | `personalDataNeverReachesTheLogs` |
| Hexagonal dependency rules | NFR9 | `ArchitectureTest` |
| Unsafe settings stop the service at startup (publish timeout above 4 s, lease not longer than the timeout) | NFR4 | `IntakePropertiesTest` |
| Health probe endpoints used by Container Apps exist | Ops | `containerAppsProbeEndpointsExist` |

## What is stubbed and how

| Dependency | Port | Stub (profile `!azure`) | Production adapter (not built) |
|---|---|---|---|
| Azure Service Bus topic | `EventPublisher` | `InMemoryEventPublisher`, wrapped by the real `TimeLimitedEventPublisher` | `ServiceBusSenderClient` + `DefaultAzureCredential`, `messageId = idempotencyKey` |
| Azure SQL (`inbound_event`, `inbound_attempt`) | `IdempotencyStore` | `InMemoryIdempotencyStore` (same lease semantics) | JDBC with Entra ID token auth; schema in [`db/migrations`](../db/migrations); `INSERT`, then a conditional `UPDATE` of `claimed_until` on a primary-key violation |
| Azure Key Vault | `SecretProvider` | `EnvironmentSecretProvider` (environment variables) | `SecretClient` with managed identity, 5-minute cache, current + next secret |
| Application Insights | `IntakeMetrics` | — (real Micrometer adapter; registry exported by the Azure Monitor agent) | same |
| Clock | `java.time.Clock` | `MutableClock` in tests | `Clock.systemUTC()` |

Stubs are only wired outside the `azure` profile and log a `WARN` at startup, so a stubbed build cannot run unnoticed in Azure.

## Known gaps

- Real adapters for Service Bus, Azure SQL (with Flyway migrations) and Key Vault are not implemented; only their contracts and semantics, exercised through the stubs.
- Validation of the API Manager's machine-to-machine token (A-4) is assumed to be platform configuration and is not in the code.
- OpenTelemetry trace propagation into Service Bus is described in `OPERATIONS.md`, not wired; the freshness metric belongs to the recorder, which is not built.
- No load test; the capacity figures in the design are calculations.
- Delivery is at-least-once: a publish that times out but still reaches the broker, or a failed "mark as published", can publish the same event twice. Consumers deduplicate on `idempotencyKey` (A-11); a transactional outbox would remove this.
