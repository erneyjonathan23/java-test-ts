# Assumptions

> The scenario is intentionally incomplete. Record every assumption you needed to make, and the part it affects.

| # | Assumption | Why I needed it | Part(s) affected |
|---|---|---|---|
| A-1 | The API Manager forwards the request body **byte for byte** (no re-serialization, no charset conversion) together with the provider signature headers. | HMAC is computed over the raw body; any transformation breaks verification. | A, B |
| A-2 | There is private connectivity (Private Link / VPN) from the integration platform to the Azure VNet, so Azure services can disable public access. | Trust boundaries and network design. | A, C |
| A-3 | Providers may not publish fixed source IP ranges; IP allow-listing is used only where a provider publishes them, and the HMAC is the primary authenticity control. | Not stated in the specifications. | A, C |
| A-4 | The API Manager obtains an IdP machine-to-machine token and forwards it to the intake, which validates it (audience `payments-intake`). | "Internal APIs require a token from the IdP"; prevents bypassing the API Manager. In Part B this check is out of scope of the code (handled by platform config). | A, B |
| A-5 | Each provider webhook secret is stored in Key Vault as a list (current + next) to support rotation without downtime; Quetzal also sends two signatures during rotation. | Secret rotation is not described for Andina and Austral. | A, B, C |
| A-6 | Quetzal amounts use 2 decimals for MXN (minor unit = centavo). A currency-exponent table (MXN 2, COP 2, CLP 0) is used for any other currency. | The spec only shows MXN. | A, B |
| A-7 | Andina `REVERSADA` and Austral `PAYMENT_REVERSED` reverse the **total** amount (no partial amount field is provided). | Specs do not mention partial reversals. | A, B |
| A-8 | A bare 9-digit Andina reference (e.g. `000215560`) is the policy number without prefix and can be normalized to `POL-CO-000215560`. Anything else is treated as invalid and parked, never guessed. This must be validated with the business before go-live. | Andina 2.3 stops normalizing the reference (INC-0412). | A, B, D |
| A-9 | A partial refund (`amount_refunded` < `amount`, Quetzal) is recorded as `Refunded` with `amountRefunded` in the canonical event, because the `Status__c` picklist has no partial state. To be confirmed with the Salesforce team (the flow recalculates `Overdue_Amount__c`). | Picklist only has 4 values. | A, B |
| A-10 | Operational data (no PII) is retained 13 months for reconciliation with Finance; delivery attempts 90 days. | No retention policy was given. | A |
| A-11 | Downstream consumers (Finance, Notifications) accept at-least-once, possibly out-of-order delivery and deduplicate on `idempotencyKey`; they do not need payer personal data in the event. | FR6 says they subscribe on their own. | A, B |
| A-12 | The 97% Salesforce availability is measured monthly and its outage windows are independent of our components; the 99.5%-in-1-hour SLO is reported as at risk when an outage exceeds 60 minutes rather than "fixed" by the integration. | NFR2 vs NFR5 are partly contradictory. | A, D |
| A-13 | The 10-minute (Andina) and 300-second (Quetzal) timestamp windows apply to the time of **our** check; retries carry a fresh timestamp/signature, so a legitimate retry is never rejected as stale. | Needed to choose the replay-protection rule. | B |
| A-14 | Andina may answer `200` or `204`; we answer `200` with no body for every provider. | Simplicity; satisfies all three specs. | B |
| A-15 | Webhook bodies are at most 64 KB. | Size limit at the API Manager and in the intake. | A, B |
| A-16 | The Azure region is one region serving the three countries (as stated); data-residency rules of the three countries allow processing there because no payer personal data is stored by the integration. | NFR6 and per-country data protection law. | A, C |
| A-17 | Each country's notifications are within the same Salesforce org; policy numbers are unique across countries thanks to the `POL-CC-` prefix. | Needed for the payment key and the reference normalization. | A |
| A-18 | The platform/security policy does not allow public data-plane endpoints for confidential workloads in production; hence Service Bus Premium (private endpoint) in prod. If a public endpoint with Entra-only authentication is accepted, Standard would be used (cost trade-off in AZURE_SERVICES.md). | Private endpoints are a Premium-only feature of Service Bus. | A, C |
| A-19 | GitHub is the source control and CI/CD platform, with OIDC federation to Azure and self-hosted runners available inside each environment's VNet for data-plane steps. | Pipeline design; private endpoints are not reachable from GitHub-hosted runners. | C |
