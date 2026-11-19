# API and event contracts

| Contract | File | Audience |
|---|---|---|
| Webhook endpoints (OpenAPI 3.0) | [`webhooks.openapi.yaml`](webhooks.openapi.yaml) | Payment providers, API Manager team |
| Canonical event (JSON Schema 2020-12, CloudEvents 1.0 envelope) | [`events/premium-payment.schema.json`](events/premium-payment.schema.json) | Finance, Notifications, our recorder |

Contracts come first: the implementation in `practical/` is tested against the event schema (every event produced from the providers' samples is validated against it).

## How the webhook contract evolves without breaking providers

- **We do not control the providers' payloads; we adapt to them.** Each provider has its own adapter. Our parsers ignore unknown fields (Andina 2.3 added `canal`) and validate only what we need.
- **Provider API versions** (Quetzal `api_version`, Andina `2.x`) are read from the changelog before they take effect. A provider version change that alters semantics (Andina 2.3 stopped normalizing `referencia`) is handled as a new adapter version behind a feature flag and tested with the provider's sandbox before the effective date. The provider changelogs are a release-calendar input for the squad (INC-0412).
- **Endpoint versions** live in the API Manager base path (`/payments/v1`). A breaking change for a provider (e.g. a new URL) is exposed as `/v2` while `/v1` keeps working until the provider has migrated.
- **Signature schemes and secret rotation** are handled without downtime: several secrets are accepted at the same time during rotation.

## How the event contract evolves without breaking consumers

- **Versioning:** `dataschema` (and the schema `$id`) carry the version; `data.schemaVersion` repeats it inside the payload.
  - *Minor* (`1.0 → 1.1`): only additive, optional fields or new enum values documented as "may appear". Same topic. Consumers must ignore unknown fields.
  - *Major* (`1.x → 2.0`): removing/renaming fields, changing meaning or types. Published **in parallel** on the same topic with `dataschema …/2.0` (consumers filter on the `dataschema` application property) until every consumer has migrated; then 1.x is retired with notice.
- **Compatibility checks in CI:** the schema is linted, and a backward-compatibility check fails the build if a required field is added or an existing field is removed or changed.
- **Delivery semantics are part of the contract:** at least once, not ordered. Consumers deduplicate on `data.idempotencyKey` and order on `data.statusChangedAt` with the status precedence `PENDING < CAPTURED|FAILED < REFUNDED`.
- **Privacy is part of the contract:** the event never carries payer personal data; adding any personal field is a major change that requires a privacy review.
