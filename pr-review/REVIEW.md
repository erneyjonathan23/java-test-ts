# Review — PR-214

> Line numbers refer to the **new** files (the `+` side of the diff). Paths are relative to `src/main/java/com/meridian/payments/` unless they start with `src/`.

## Verdict

- [ ] Approve
- [ ] Approve with comments
- [x] Request changes

## Summary for the author

Hi @dvaldes — thanks for picking up Chile before the month-end peak. The structure is easy to follow, the signature is verified with the right algorithm and the provider's own `eventId` is used for idempotency, which is the correct key for Austral. Disclosing the Copilot-generated tests is appreciated too.

I can't approve it yet, because in this flow a wrong status changes a customer's coverage (the Salesforce flow reinstates or suspends policies), and a few issues can lose payments, show a reversed payment as captured, or expose secrets and personal data. The ones that must change before merge are:

1. Rotate the live secret now (it is already in the branch history), then read it from Key Vault (#1).
2. Never skip signature verification (#2).
3. Only answer `200` once the notification is durable, and stop swallowing errors (#3, #4).
4. Do not log payer data (#5).
5. Protect against out-of-order events (#6).

Next most important are the find-then-create race and the API cost (#7), money as `double` (#8) and tests that really assert behaviour (#11). Happy to pair on any of this. The design doc's intake → queue → state-sync pattern covers #3, #6 and #7 together, so we could also reshape the prototype in that direction.

## Findings

| # | Severity | File:line | Finding | Why it matters in this scenario | Suggested fix |
|---|---|---|---|---|---|
| 1 | Blocker | `src/main/resources/application.yml:19` | A **live** Austral webhook secret (`aus_live_…`) is committed in plain text. "Move it later" does not undo the leak: it is already in the branch history and in every clone/CI log. | Anyone with repo access can forge Chilean payment notifications → fake `Captured` payments → policies reinstated without payment (NFR6). | Treat it as compromised: **rotate it in the Austral portal today**, purge it from the branch (or accept and rotate), load it from Key Vault through the managed identity (`${AUSTRAL_WEBHOOK_SECRET}` placeholder locally). Add secret scanning to CI. |
| 2 | Blocker | `cl/AustralWebhookController.java:44` | `if (signature != null && !verifier.isValid(...))`: a request **without** `X-Austral-Signature` skips verification and is processed. `required = false` on line 41 makes this explicit. | Unauthenticated payment notifications from the internet (FR1). | Header required; missing/blank → `401`. Verification must be unconditional: `if (signature == null \|\| !verifier.isValid(rawBody, signature)) return 401;`. Add a test for the missing header. |
| 3 | Blocker | `cl/AustralWebhookController.java:53-55`, `config/AsyncConfig.java:14` | `200` is returned after handing the work to an **in-memory** thread pool. A restart, deployment, scale-in or crash loses every queued notification, and Austral will not retry because it got `2xx`. `newFixedThreadPool(4)` also has an **unbounded** queue: at the 40/s peak with Salesforce at 1–8 s per call, 4 threads cannot keep up and memory grows until the pod dies, losing everything queued. | NFR3: an accepted notification must never be lost. Month-end is exactly when this would happen. | Make it durable before answering: publish to Service Bus (or at least insert into a table) and return `200` only after that succeeds, `503` otherwise so Austral retries. Process from the queue with retries and a DLQ. |
| 4 | Blocker | `cl/AustralPaymentService.java:58-59` | Every exception (Salesforce down, `REQUEST_LIMIT_EXCEEDED`, DB error) is caught and logged; nothing is retried and the event is not marked, so the payment is silently dropped. The test `shouldHandleErrors` asserts this behaviour as correct. | Salesforce is ≈ 97% available: with this code, every payment received during an outage is lost (≈ 3% of payments). Customers who paid stay suspended. | Let failures propagate to the queue consumer: retry transient errors with backoff, dead-letter non-retryable ones, alert on the DLQ. Never catch `Exception` without re-throwing or recording the failure. |
| 5 | Blocker | `cl/AustralWebhookController.java:50` | `log.info("…{}", notification)` logs the record's `toString()`, which includes the **payer's name, RUT and e-mail**. | NFR6 and Chile's personal data law: personal data must not appear in logs/telemetry (and the logs go to a shared workspace). | Log only identifiers (`eventId`, `paymentId`, `eventType`, correlation id). Better: drop `Payer` from the model (#13) so it cannot be logged. |
| 6 | Blocker | `cl/AustralPaymentService.java:43-45` | Ordering is not handled: whatever event arrives last wins. Austral says *ordering is not guaranteed*; a delayed `PAYMENT_CONFIRMED` processed after `PAYMENT_REVERSED` sets the payment back to `Captured`. `updatePaymentStatus` also does not write `Status_Changed_At__c` or `Last_Event_Id__c`, so there is nothing to compare against. | FR5. The Salesforce flow reinstates the policy on `Captured`: a customer whose payment was reversed gets coverage back (and is notified). | Apply a precedence rule (`Pending` < `Captured`/`Failed` < `Refunded`, then `occurredAt`) against the stored state before writing, and write `Status_Changed_At__c` and `Last_Event_Id__c`. See ADR-0003. |
| 7 | Major | `cl/AustralPaymentService.java:43-55` | Find-then-create is not atomic: two events of the same payment processed concurrently (4 threads) both see "not found" and create **two** records. It also costs 2 API calls per notification and does not use `Payment_Key__c`, the external id meant for upserts. | FR4 (exactly once in business terms), and the shared 150k/day API budget (NFR5): doubling calls at 120k notifications/day exceeds it. | Upsert by external id `Payment_Key__c = "AUSTRAL:" + paymentId` in one call (`PATCH …/Payment_Key__c/{key}`), ideally batched (composite, 200 per call). |
| 8 | Major | `cl/AustralNotification.java:11`, `cl/AustralPaymentService.java:49` | Money as `double`, then `Math.round(amount * 100) / 100.0` → `BigDecimal.valueOf(double)`. Binary floating point plus a 2-decimal rounding that does not apply to CLP (no minor unit). | Amounts decide whether `Overdue_Amount__c` is covered and the policy reinstated; floating point and wrong scale are a correctness risk on financial data. | Bind `amount` as `String`/`BigDecimal`, validate it is an integer for CLP, use `new BigDecimal(amount).setScale(0, UNNECESSARY)` with a per-currency exponent. |
| 9 | Major | `cl/AustralPaymentService.java:35,57` | The idempotency check is check-then-act: `existsById` at the start, `save` at the end after the Salesforce calls. Concurrent retries of the same `eventId` both pass the check. If `save` fails, the exception is swallowed (#4). | Duplicates reach Salesforce (FR4), and Austral re-deliveries are common (at least once, 8 retries). | Claim the key first with an `INSERT` (primary key violation = duplicate), then process. Or rely on an idempotent upsert plus the precedence rule (#6, #7). |
| 10 | Major | `cl/AustralSignatureVerifier.java:21-26`, `cl/AustralWebhookController.java:40` | (a) `expected.equals(signature)` is not constant time. (b) The HMAC is computed over `String.getBytes(UTF_8)` of a body that Spring already decoded into a `String`, not over the raw bytes; any charset or decoding difference breaks valid signatures or opens ambiguity. (c) Any exception returns `false`, so a misconfigured secret looks like an attacker (`401`), not an outage. | FR1 says verify *exactly* as specified (raw body). Timing side channels on a public endpoint. | Take `@RequestBody byte[]`, compare with `MessageDigest.isEqual` on decoded bytes, accept several secrets for rotation, treat configuration errors as `5xx`. Test with `reference/providers/austral-cl` vectors. |
| 11 | Major | `src/test/java/com/meridian/payments/cl/AustralPaymentServiceTest.java:35,44,51` | The tests pass but prove nothing: `assertNotNull(service)`; `atMost(1)` is true even if `createPayment` is called once on a duplicate (should be `never()`); `assertDoesNotThrow` enshrines the data-loss bug (#4). There are no tests for the signature (with the provider's test vectors), the controller, ordering or amounts. | "All green" gives false confidence on the riskiest code we have. AI-generated tests need the same review as code. | Replace with behaviour tests: signature vectors (valid, tampered, missing header), duplicate ⇒ `never()` calls Salesforce, reversal-then-confirm keeps `Refunded`, amount `"45990"` CLP ⇒ `45990`, Salesforce failure ⇒ exception/retry. |
| 12 | Major | `cl/AustralPaymentService.java:68` | Unknown `eventType` defaults to `"Pending"`. | A new event type (e.g. a chargeback) would be written as `Pending`, possibly overwriting `Captured`, which changes coverage. | Reject unknown types explicitly (`422` at intake + alert); never default a financial status. |
| 13 | Minor | `cl/AustralNotification.java:18-22` | `Payer` (name, RUT, e-mail) is deserialized and kept in memory although nothing uses it. | Data minimisation; it is the root of #5. | Remove `Payer` from the record (unknown properties are ignored by Spring Boot's `ObjectMapper`). |
| 14 | Minor | `cl/AustralPaymentService.java:32` | `@Transactional` wraps slow remote Salesforce calls, so a DB connection and transaction are held for up to 8 s each; with the catch-all, the transaction always commits. | Connection pool exhaustion under the peak. | Keep transactions short (claim key, update state); call Salesforce outside them. |
| 15 | Minor | `cl/AustralPaymentService.java:47-54` | `policyNumber` is sent as received, with no validation or normalization. | INC-0412 (Colombia) was caused by exactly this when a provider changed the reference format. | Validate `POL-CL-\d{9}` (upper-case, trimmed); park invalid ones with an alert instead of failing in Salesforce. |
| 16 | Minor | `cl/AustralWebhookController.java:49` | Malformed JSON with a valid signature throws and returns `500`; there is no correlation id and no metric of accepted/rejected notifications. | FR7/NFR7: operations cannot follow a notification without searching raw logs. | Map to `400`; add a correlation id (MDC + response header) and counters per outcome. |
| 17 | Nit | `cl/AustralPaymentService.java:59` | String concatenation in the log call. | — | `log.error("… eventId={}", id, e)`. |
| 18 | Nit | `config/AsyncConfig.java:14` | Executor is not a managed `ThreadPoolTaskExecutor` (no graceful shutdown, no thread names). | Moot if #3 moves processing to a queue consumer. | — |

Severity scale:

| Severity | Meaning |
|---|---|
| Blocker | Must be fixed before merge: data loss, security exposure, wrong business outcome for customers, or compliance breach |
| Major | Should be fixed before merge: reliability, correctness in edge cases, or maintainability risk with real impact |
| Minor | Can be fixed in this PR or a follow-up |
| Nit | Style or naming; optional |

## What looks good

- `eventId` is the right idempotency key for Austral (stable across retries), and the table already exists on `main`.
- HMAC-SHA256 with hex output is the algorithm Austral specifies.
- Answering fast instead of calling Salesforce in the request thread is the right instinct; it just needs a durable hand-off.
- The status mapping for the three known event types is correct.
- The PR description is clear and honest about the AI-generated tests and the pending secret move. Both are easy to act on.
