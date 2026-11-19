# INC-0412 — Response

> On-call engineer, Monday 2026-11-30 08:15. All times Colombia time (UTC−5).

## 1. First 30 minutes

| Time | What I check / do | Why |
|---|---|---|
| 08:15 | Acknowledge, open the incident channel, confirm **SEV-2** (SEV-1 if the impact is in the thousands, see below). Tell the Colombia contact-center lead we are on it. | Paid customers without coverage: customer and regulatory impact. |
| 08:20 | Scope from the snapshot: only Colombia (MX/CL DLQ 2/0); CO volume normal (37,215 vs 36,480), 0 signature failures, main queue empty. **CO DLQ = 4,312** and CO Salesforce **`400`s = 43,120 = 4,312 × 10**: every failed message was tried 10 times. | Not intake, volume or authentication: Salesforce rejects data for one country. The 37 cases are the tip: **4,198 approved payments** are stuck. |
| 08:25 | DLQ samples and consumer log: `400 INVALID_FIELD: Foreign key external ID: 000215560 not found`, `pol-co-000198004`. References are **not** `POL-CO-NNNNNNNNN`. Redeliveries 0.25 s apart (no backoff). | The lookup fails because the reference is malformed, not because the policy is missing. |
| 08:30 | Recent changes: first DLQ **Sat 23:41**; **Andina API 2.3 took effect Sat 23:00** ("`referencia` is delivered exactly as entered by the payer… merchants should normalize"). The Salesforce release (22:00) adds a rule on `Policy__c` *cancellation*: it would fail with `FIELD_CUSTOM_VALIDATION_EXCEPTION` and hit MX/CL too. `v1.14.0` (Fri 18:05) predates the failures by 30 h and only changed logging. | Separates the cause from two red herrings. |
| 08:35 | **Stop the bleeding**: the API budget is at 66% (38% normally) because of the retries, and the org-wide limit is shared with the contact center. Temporarily lower `maxDeliveryCount` on `payments-co` to 2 (valid messages succeed first time; transient errors dead-letter sooner, acceptable because the DLQ is replayable and now watched). Warn the Salesforce team. Add DLQ alerts for `payments-co` and `payments-cl`. | Exhausting the org limit would take down every integration. |

## 2. Most likely root cause

**Andina 2.3 stopped normalizing `transaccion.referencia` on 2026-11-28 23:00. v1 used the reference unchanged as the external id of `Policy__c`, so every payment whose payer typed the policy number in another format was rejected with `400 INVALID_FIELD`. v1 retries every Salesforce failure 10 times immediately and dead-letters it; there was no DLQ alert for Colombia.**

Evidence: timing (23:00 change → 23:41 first DLQ, nothing before); only the provider that changed is affected; the error is a foreign-key lookup on `Policy_Number__c`; the sample references are recognisable policy numbers; valid CO notifications (32,880) still succeed; `400`s = DLQ × 10.

Confirmation (read only): (1) 100% of DLQ `referencia` values fail `^POL-CO-\d{9}$` and no successful CO upsert since 23:00 does; (2) 20 normalized samples (`000215560` → `POL-CO-000215560`) exist in Salesforce as `Suspended` with the matching overdue amount; (3) the 37 cases' transaction ids are in the DLQ; (4) Andina confirms 2.3 went live as announced.

Contributing factors: no reference validation at intake; non-retryable `400`s retried without backoff; alerts created by hand per country; provider changelogs not tracked.

## 3. Remediation

1. **Fix forward today**: normalize CO references (trim, upper-case, prefix 9-digit values; anything else parked and alerted, never guessed, A-8) and treat `INVALID_FIELD`/validation errors as **non-retryable**. Tests with the incident's references; expedited release through the normal pipeline.
2. **Reprocess the 4,312 messages safely**:
   - **Export, don't delete**: peek the DLQ to a protected table; the DLQ stays the source of truth until the end.
   - **Deduplicate** by `transaccion.id` + `estado` (retries have new `notificacion_id`s).
   - **Collapse to the latest state per transaction** (`PENDIENTE < APROBADA/RECHAZADA < REVERSADA`, then `fecha_transaccion`). `TRX-CO-5603318` has both `APROBADA` and `REVERSADA`: replayed in arrival order by v1, it could end `Captured` and **reinstate a policy whose payment was reversed**. It is written once, as `Refunded`.
   - **Never regress Salesforce**: skip a transaction whose record has a newer `Status_Changed_At__c`.
   - **Normalize references**; unresolvable ones go to the Colombia operations team, not to Salesforce.
   - **Dry run** report (final statuses, normalized vs unresolved, expected reinstatements) reviewed by a second engineer; timing agreed with the **Salesforce team** (≈ 4,000 reinstatement-flow runs and customer notifications) and the contact center.
   - **Canary**: the 37 reported customers first, verified in Salesforce (`Captured`, policy `Active`); then throttled batches, `APROBADA` first, watching `UNABLE_TO_LOCK_ROW` and the budget (≈ 4,300 calls ≈ 3%).
   - **Reconcile** expected vs written per transaction, then purge the DLQ and keep the export as evidence.
3. **Customers**: call back the 37; inform Compliance/Claims that ≈ 4,200 CO policies were wrongly suspended from Sat 23:41 until the replay, so claims in that window are honoured.

## 4. Prevention

| What | Would have… |
|---|---|
| DLQ, freshness and "rejected by Salesforce" alerts **per country from one IaC template** | paged at 23:41 Saturday instead of 08:12 Monday |
| Reference normalization at intake + alert on the `referenceStatus` ratio | avoided it, or detected the format change in minutes |
| Error classification (data errors never retried) and exponential backoff | saved ≈ 39,000 calls of the shared budget |
| Latest-state sync with a precedence rule (ADR-0003) | made the replay safe by construction |
| Provider changelog review (owner per provider, calendar of effective dates) + contract test against the sandbox | caught Andina 2.3 the week before |
| Budget alert at 50% | warned on Sunday |

Blameless post-incident review within 5 working days; every action item with an owner and a date.
