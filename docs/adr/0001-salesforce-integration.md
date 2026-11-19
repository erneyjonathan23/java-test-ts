# ADR-0001: Integration with Salesforce

- **Status:** Accepted
- **Date:** 2026-11-19

## Context

Every payment must become a `Premium_Payment__c` in Salesforce (FR3) exactly once in business terms (FR4) and never in a state older than the provider's latest one (FR5), because a wrong `Status__c` reinstates or suspends a customer's coverage through the Salesforce team's flow.

Salesforce constraints ([`API_CONSTRAINTS.md`](../../reference/salesforce/API_CONSTRAINTS.md)):

- ≈ 97% availability, outages of 5–90 minutes (≈ 22 h a month).
- 150,000 calls per rolling 24 h for this integration, inside an **org-wide** limit shared with the contact center and partners. Exceeding it returns `403 REQUEST_LIMIT_EXCEEDED` to **every** integration.
- 25 concurrent requests; single upsert p95 1.2 s (up to 8 s); 200-record composite upsert p95 4 s and counts as **one** call.

Load (NFR1): up to 120,000 notifications a day and 40/s bursts for 15 minutes. Freshness (NFR2): 95% in 5 minutes, 99.5% in 1 hour.

## Options considered

### Option 1 — Synchronous write from the webhook request
The intake writes to Salesforce before answering the provider.
- Pros: simplest; the provider's retry is the retry mechanism.
- Cons: Salesforce p95/peak latency (up to 8 s) breaks Andina's 5 s timeout; every Salesforce outage becomes provider retries, which are limited (Andina: 5 in 24 h) → payments lost on the provider side; 40/s bursts exceed what 25 concurrent requests sustain; one call per notification (≈ 116k on a peak day, 78% of the budget before any retry). Couples our availability to Salesforce's 97%. **Rejected.**

### Option 2 — Asynchronous, one upsert per message (INC-0412 "v1")
A queue consumer upserts one record per message.
- Pros: decouples providers from Salesforce; simple consumer.
- Cons: still one call per notification; immediate redelivery turns outages and data errors into call storms (INC-0412: 43,120 failed calls, 66% of the budget); ordering between concurrent consumers is not guaranteed, so an older state can overwrite a newer one.

### Option 3 — Asynchronous, latest-state sync in batches (composite upsert of up to 200 records)
Events update a local `payment_state`; a single sync worker periodically sends the latest state of changed payments to Salesforce with `PATCH /composite/sobjects/Premium_Payment__c/Payment_Key__c` (`allOrNone: false`).
- Pros: calls bounded by time (≤ 1 per 5 s cycle under normal load ⇒ ≤ ~17k/day, ~12% of the budget) instead of by volume; 250 records/s capacity with 5 concurrent requests; per-record results let us retry only what is retryable; writing the current state from a single writer makes ordering correct by construction; Salesforce outages just accumulate dirty rows.
- Cons: up to 5 s of added latency; a local state store to operate; batch error handling is more complex (partial failures); a single active writer must be made highly available (SQL application lock + fast restart).

### Option 4 — Salesforce Platform Events / Pub/Sub API, or Bulk API 2.0
- Platform Events: Salesforce would own the upsert logic in Apex/flows; event allocations are another limited, shared budget and the payments squad does not own the org. Bulk API 2.0: asynchronous jobs with minutes of latency, designed for large volumes, not for a 5-minute freshness target. **Rejected** for now (Bulk API 2.0 is kept as the mechanism for large backfills/reconciliation).

## Decision

**Option 3.** Asynchronous integration, batched composite upserts by external id `Payment_Key__c`, written from the latest local state by a single active sync worker, protected by:

1. **Budget guard:** rolling 24 h call counter in SQL; warn at 50%, stop non-essential calls and page at 80% of 150,000.
2. **Circuit breaker:** opens after 5 consecutive `5xx`/timeouts (probe every 30 s); `403 REQUEST_LIMIT_EXCEEDED` pauses all calls for 15 minutes and pages.
3. **Classification of errors per record:** `UNABLE_TO_LOCK_ROW`/`5xx` retried with exponential backoff and jitter for up to 72 h; data errors (`INVALID_FIELD`, validation rules) are **never** retried automatically and are parked as `NEEDS_ATTENTION`.
4. **Concurrency cap:** at most 5 of the 25 agreed concurrent requests.
5. **Token caching:** one OAuth token reused for ~2 h.

## Consequences

- Positive: the API budget is predictable and independent of duplicates and outages; Salesforce outages never lose or reorder payments; one record per payment by design (upsert by external id).
- Negative: freshness has a floor of ~5–11 s; more moving parts than v1 (state table, leader election); partial-batch failures need careful handling and tests; the 99.5%-in-1-hour target cannot be guaranteed when Salesforce outages last longer than 60 minutes (reported in OPERATIONS.md).
- Risks and mitigation:
  - *Single writer stalls* → the SQL application lock is released when the replica dies and another replica takes over; Container Apps restarts the failed one; alert when the oldest dirty row is older than 2 minutes.
  - *Salesforce validation rule changes reject records* (INC-0412 type) → per-country alert on `NEEDS_ATTENTION` rate, and the reference normalization in the intake.
  - *The flow on `Premium_Payment__c` slows down 200-record batches* → batch size is configuration; reduce to 50 if p95 > 10 s, agreed with the Salesforce team.
