# Operations — Premium Payment Notifications

## 1. SLIs and SLOs (per country: MX, CO, CL; 28-day rolling window)

| SLI | Definition (measured from our own data) | SLO | Source |
|---|---|---|---|
| **Freshness 5 min** | Share of accepted notifications whose payment state is confirmed in Salesforce ≤ 5 min after the intake answered `2xx`. Superseded and duplicate notifications count as on time. | **≥ 95%** (NFR2) | `payments.freshness.seconds{country}` = `synced_at − first_received_at`, emitted by the sync per record |
| **Freshness 1 h** | Same, ≤ 1 hour. | **≥ 99.5%** (NFR2) — *at risk* when Salesforce outages exceed 60 min (design §7); reported both raw and excluding Salesforce-declared outages | same |
| **No loss** | Accepted notifications (`inbound_event` rows) without a final processing status (`RECORDED`, `SUPERSEDED`, `NEEDS_ATTENTION`, `FAILED_PERMANENT`) 24 h after acceptance. | **0** (NFR3). Any value > 0 is an incident, not budget consumption | hourly reconciliation query |
| **Completeness vs provider** | Payments in the provider's daily settlement report that are not in `inbound_event`. | **0** after D+1 | daily job, provider report per country |
| **Intake availability** | Correctly signed requests answered `2xx` within the provider timeout / all correctly signed requests. | **≥ 99.9%** | API Manager + intake request telemetry |

Error-budget policy: when a country burns > 50% of its freshness budget in a week, feature work for that flow stops until the cause is fixed; any loss SLI breach gets a post-incident review.

## 2. Instrumentation

**Correlation id, end to end.** The API Manager sets `X-Correlation-Id` (if the provider did not) → the intake validates its format, puts it in the logging context (MDC) and returns it → it travels in the canonical event (`correlationid`) and as the Service Bus `CorrelationId`, together with the W3C `traceparent` (OpenTelemetry) → the recorder restores it in its MDC and stores it in `inbound_event.correlation_id` → the Salesforce sync logs it per record of each composite request, next to `Payment_Key__c`. Operations can therefore go from a provider id (ops API) to the correlation id and to the full trace in Application Insights, and from a Salesforce record (`Payment_Key__c`, `Last_Event_Id__c`) back to the notification.

**Traces**: Application Insights Java agent (OpenTelemetry) — inbound HTTP, Service Bus send/receive, JDBC, outbound Salesforce calls (URL without query string).

**Logs** (structured JSON, `INFO` by default): one line per decision — `accepted`, `duplicate`, `rejected{reason}`, `applied`, `superseded`, `recorded`, `needs_attention{sfErrorCode}`, `dead-lettered{reason}` — with `correlationId`, `country`, `provider`, `providerEventId`, `idempotencyKey`, `paymentKey`, canonical `status`, `referenceStatus`, Salesforce status/error code.

**Metrics** (Micrometer → Application Insights, all tagged `country`, `provider`): `intake.accepted{outcome}`, `intake.rejected{reason}`, `intake.latency`, `recorder.applied{result}`, `sync.dirty.count`, `sync.oldest_dirty.age`, `sf.calls{status}`, `sf.budget.used.ratio`, `payments.freshness.seconds`, `intake.reference{status}`; Service Bus active/dead-lettered counts per subscription.

**Never logged, traced or measured**: payer names, national ID numbers, e-mails, or any `pagador`/`payer`/`customer` field; raw request bodies or full headers (signatures); secrets, OAuth tokens, payment tokens; Salesforce response bodies beyond the error code. Enforced by design (the adapters never read payer fields, the canonical event has none) and by a test that fails if sample personal data appears in the logs. Application Insights request telemetry drops request bodies and the `Authorization` header.

## 3. Alerts

Paging = the Payments Integration on-call (PagerDuty-style rotation through the action group). Defined in `infra/modules/monitoring.bicep`.

| Alert | Condition | Severity / who |
|---|---|---|
| Dead-lettered message | DLQ count > 0 on **any** subscription, any country | Sev 1 in prod → page on-call |
| Sync stalled | Oldest dirty `payment_state` > 10 min | Sev 1 → page |
| Freshness | p95 freshness > 5 min for 15 min, per country | Sev 2 → page |
| Data rejected by Salesforce | `NEEDS_ATTENTION` > 5 in 15 min, per country | Sev 2 → page; Salesforce team and country ops lead informed |
| Provider contract change | Any `422` (unknown state/type) or `referenceStatus` NORMALIZED/INVALID ratio > 5% of the last hour, per country | Sev 2 → page (this would have caught INC-0412 at 23:41) |
| Signature failures | > 10 in 15 min per provider | Sev 2 → page; security team informed if sustained |
| Intake errors | `5xx` > 1% for 5 min | Sev 1 → page |
| Salesforce API budget | > 50% of 150k rolling 24 h → ticket; > 80% → page (sync stops itself); any `REQUEST_LIMIT_EXCEEDED` → page + Salesforce team | Sev 3 / 1 |
| Traffic drop | 0 accepted notifications for 30 min, per country, 07:00–22:00 local | Sev 3 → ticket, page if it persists 1 h |
| Loss SLI | Unaccounted accepted notifications > 0 at the hourly reconciliation | Sev 1 → page |

Every alert links to a runbook section (what it means, first queries, safe actions). Alerts are created **per country by the same template** — a new country cannot go live without them (INC-0412 had a DLQ alert for Mexico only).
