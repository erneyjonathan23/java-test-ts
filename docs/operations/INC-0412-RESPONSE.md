# INC-0412 — Response

> On-call engineer, Monday 2026-11-30 08:15 (Colombia time). All times in Colombia time (UTC−5).

## 1. First 30 minutes

| Time | What I check / do | Why |
|---|---|---|
| 08:15–08:20 | Acknowledge, open the incident channel, confirm **SEV-2** (escalate to SEV-1 if the impact is in the thousands, see below). Tell the Colombia contact-center lead that we are on it and that no customer action is needed. | Paid customers without coverage is a customer and regulatory impact; the contact center needs a message now. |
| 08:20–08:25 | Scope from the snapshot: only Colombia is affected (MX/CL: DLQ 2/0, few `400`s); CO volume is normal (37,215 vs 36,480), signatures are fine (0 failures), the CO main queue is empty. **CO DLQ has 4,312 messages** and CO has **43,120 Salesforce `400`s = exactly 4,312 × 10**: every failed message was tried 10 times. | Tells me the problem is not intake, volume or authentication, but data rejected by Salesforce for one country. The 37 cases are the tip: **4,198 approved payments** are stuck. |
| 08:25–08:30 | Read the DLQ samples and the consumer log: `400 INVALID_FIELD: Foreign key external ID: 000215560 not found` and `pol-co-000198004`. References are **not** in the `POL-CO-NNNNNNNNN` format. Redeliveries are 0.25 s apart (no backoff). | The policy lookup fails because the reference is malformed, not because the policy does not exist. |
| 08:30–08:35 | Match against recent changes: first DLQ message **Sat 23:41**; **Andina API 2.3 took effect Sat 23:00**: "`referencia` is now delivered exactly as entered by the payer… merchants should normalize". The Salesforce release (Sat 22:00) adds a validation rule on **`Policy__c` cancellation** — it would give `FIELD_CUSTOM_VALIDATION_EXCEPTION`, not a foreign-key error, and would affect MX/CL too. `v1.14.0` (Fri 18:05) predates the first failure by 30 h and only changed logging. | Separates the cause from the two red herrings. |
| 08:35–08:45 | **Stop the bleeding**: API budget is at 66% (vs 38%) because of the 10× retries. Lower `maxDeliveryCount` on `payments-co` to 2 as a temporary config change (valid messages succeed on the first try; the trade-off is that a transient Salesforce error now dead-letters sooner, which is acceptable because the DLQ is replayable and now watched), and alert the Salesforce team that the org limit is at risk. Add the missing DLQ alert for `payments-co` and `payments-cl`. | The retry storm burns the **shared** org budget; if it is exhausted, the contact center and partners go down too. |

## 2. Most likely root cause

**Andina Pay 2.3 stopped normalizing `transaccion.referencia` on 2026-11-28 23:00. v1 used the reference unchanged as the external id of `Policy__c`, so every notification whose payer typed the policy number in any other format (lower case, digits only…) was rejected by Salesforce with `400 INVALID_FIELD`. v1 treats every Salesforce failure as retryable, so each one was retried 10 times immediately and dead-lettered; there was no DLQ alert for Colombia.**

Evidence: timing (23:00 change → 23:41 first DLQ, and nothing before); only Colombia (the only provider that changed); the error is a foreign-key lookup on `Policy_Number__c`; the sample references are recognisable policy numbers in other formats; valid CO notifications (32,880) still succeed; `400`s = DLQ × 10.

How I confirm it (read only):
1. Query the DLQ export: 100% of `referencia` values fail `^POL-CO-\d{9}$`; none of the successful CO upserts since 23:00 fails it.
2. For a sample of 20, normalize (`000215560` → `POL-CO-000215560`, upper-case) and look the policy up in Salesforce: it exists and is `Suspended` with the matching overdue amount.
3. Check the 37 contact-center cases: their Andina transaction ids are in the DLQ.
4. Ask Andina (account manager) to confirm 2.3 went live as announced.

Contributing factors: no validation/normalization of references at intake; non-retryable `400`s retried without backoff; per-country alerts not created from a template; provider changelogs not tracked by the squad.

## 3. Remediation

1. **Fix forward (today)**: hotfix the CO mapping to normalize references (trim, upper-case, add `POL-CO-` to 9-digit values; anything else parked and alerted, never guessed — A-8), and classify `400 INVALID_FIELD`/validation errors as **non-retryable** (straight to a "needs attention" DLQ reason, no retries). Unit tests with the incident's references; expedited deployment through the normal pipeline with approval.
2. **Reprocess the 4,312 dead-lettered messages safely**:
   - **Export, don't delete**: peek all DLQ messages to a protected file/table (the DLQ remains the source of truth until the end).
   - **Deduplicate** by `transaccion.id` + `estado` (Andina retries have new `notificacion_id`s).
   - **Collapse to the latest state per transaction** by precedence `PENDIENTE < APROBADA/RECHAZADA < REVERSADA`, then by `fecha_transaccion`. Example: `TRX-CO-5603318` has both `APROBADA` and `REVERSADA` in the DLQ: replaying them in arrival order with v1 (one upsert per message, no ordering guard) could leave it `Captured` and **reinstate a policy whose payment was reversed**. It must be written once, as `Refunded`.
   - **Do not regress Salesforce**: skip a transaction if Salesforce already has a record with a newer `Status_Changed_At__c`.
   - **Normalize references**; the ones that cannot be normalized go to a list for the Colombia operations team (resolved with the Andina transaction in the provider portal), not to Salesforce.
   - **Dry run** → report (records per final status, normalized vs unresolved references, expected reinstatements) reviewed by a second engineer; agree the timing with the **Salesforce team** (≈ 4,000 runs of the reinstatement flow and customer notifications) and the contact center.
   - **Canary**: replay first the 37 reported customers' transactions, verify in Salesforce that the payment is `Captured` and the policy `Active`; then the rest in throttled batches (≈ 4,300 upserts ≈ 3% of the daily budget with v1's per-record upsert), `APROBADA` first (coverage), watching `UNABLE_TO_LOCK_ROW` and budget.
   - **Reconcile** (expected vs written per transaction), then complete/purge the DLQ messages and keep the export as evidence.
3. **Customers**: contact-center call-back to the 37; Compliance/Claims informed that ≈ 4,200 Colombian policies were wrongly suspended between Sat 23:41 and the replay, so claims in that window are honoured.

## 4. Prevention

| What | Would have… |
|---|---|
| DLQ, freshness and "rejected by Salesforce" alerts **per country from one IaC template** | paged at 23:41 Saturday instead of 08:12 Monday |
| Reference normalization/validation at intake, with a `referenceStatus` metric and an alert on its ratio | avoided the incident; at least detected the format change in minutes |
| Error classification: data errors are not retried, retryable ones use exponential backoff | saved ≈ 39,000 Salesforce calls and protected the shared org budget |
| Latest-state sync with a precedence rule (ADR-0003) | made the replay safe by construction |
| Provider changelog review as a squad ritual (owner per provider, calendar of effective dates) and a contract test against each provider's sandbox before the effective date | caught Andina 2.3 the week before |
| Budget alert at 50% of the Salesforce API budget | warned on Sunday |

Blameless post-incident review within 5 working days; each action item with an owner and a date.
