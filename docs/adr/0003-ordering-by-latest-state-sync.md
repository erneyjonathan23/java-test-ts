# ADR-0003: Resolve ordering with a state-precedence rule and a single latest-state writer

- **Status:** Accepted
- **Date:** 2026-11-19

## Context

All three providers may deliver notifications out of order (FR5). A refunded payment shown as captured in Salesforce would reinstate a policy that should stay suspended. Ordering must hold even with duplicates, retries, DLQ replays and several consumer replicas, and must not cost extra Salesforce API calls (ADR-0001).

## Options considered

### Option 1 — Rely on broker ordering (Service Bus sessions, `SessionId = paymentKey`)
- Pros: FIFO per payment within Service Bus; one consumer per session at a time.
- Cons: providers already deliver out of order, so FIFO of *arrival* is not FIFO of *state changes*; one session per payment makes batching 200 payments per Salesforce call awkward; replays and DLQ re-submissions break the order anyway.

### Option 2 — Compare timestamps in Salesforce (conditional write)
Read the record, compare `Status_Changed_At__c`, write only if newer.
- Pros: Salesforce remains the only state.
- Cons: one extra read call per write (doubles the budget), race conditions between read and write, and not possible inside a composite upsert; timestamps alone do not encode that `REFUNDED` is terminal.

### Option 3 — Local precedence rule + single latest-state writer
Each event is applied to `payment_state` only if its status ranks higher (`PENDING` < `CAPTURED`/`FAILED` < `REFUNDED`), or ranks equal with a later `statusChangedAt`; a single leader-elected worker writes the current state to Salesforce.
- Pros: correct regardless of arrival order, duplicates or replays; no extra Salesforce calls; idempotent by construction; easy to test as a pure function.
- Cons: the business lifecycle is encoded in our code and must be kept in line with the providers' state machines; a single writer is a throughput and availability concern.

## Decision

**Option 3.** The precedence function is a pure, unit-tested function shared by recorder and replay tooling. The writer is a single active instance chosen through a blob lease (30 s, renewed every 10 s); its throughput (≈ 250 records/s) is far above the 40/s peak.

## Consequences

- Positive: FR5 holds by construction; replays are always safe; Salesforce is written from facts, not from event order.
- Negative: a non-standard lifecycle (e.g. a provider that can re-capture after a refund) would need a new rule; `FAILED` ↔ `CAPTURED` conflicts on the same transaction are resolved by time and flagged, which is a judgment call.
- Risks and mitigation: *clock skew between provider timestamps* only matters for equal-rank conflicts, which are logged as anomalies for review; *leader stalls* are covered by the lease expiry and an alert on the age of the oldest dirty row.
