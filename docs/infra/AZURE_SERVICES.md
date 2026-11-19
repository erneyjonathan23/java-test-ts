# Azure services

Each service is chosen against the Azure Well-Architected Framework pillars that matter most here: **reliability** (no accepted notification lost, Salesforce outages absorbed), **security** (secrets, identities, no personal data), **cost** (≈ 15k notifications/day is a small load). The code is in [`infra/`](../../infra/).

| Service | Why this one | Alternatives discarded | Tier / SKU | Reliability | Security | Cost |
|---|---|---|---|---|---|---|
| **Azure Container Apps** (intake, recorder, ops) | Runs the Java services with HTTP and KEDA (Service Bus backlog) autoscaling, revisions for blue/green, managed identities, VNet integration; no cluster to operate. | **AKS**: cluster operations for 3 small services. **Azure Functions**: cold starts vs Andina's 5 s timeout unless always-ready instances are paid for; awkward for a long-running leader-elected sync loop. **App Service**: no event-driven scaling on Service Bus. | Consumption workload profile; intake 0.5 vCPU/1 GiB, 2–10 replicas; recorder 0.5/1 GiB, 2–5; ops 0.25/0.5 GiB, 1–2 | Min 2 replicas in prod, zone-redundant environment, health probes, revisions with rollback | Internal-only environment, user-assigned identities, no secrets in env vars except the App Insights connection string | Pay per vCPU-s/GiB-s; always-on minimum ≈ 5 replicas |
| **Azure Service Bus** (topic + subscriptions) | Durable broker with per-subscription dead-letter queues, peek-lock, scheduled redelivery, SQL filters for targeted replays, Entra ID auth. Pub/sub fits FR6 (Finance and Notifications subscribe themselves). | **Event Grid**: push delivery with limited retry semantics and no peek-lock; good for notifications, weaker as a work queue. **Event Hubs**: partitioned log, consumers manage checkpoints, no per-message DLQ — over-engineered for 1.4 msg/s average. **Storage queues**: no topics, no DLQ. | **prod: Premium 1 MU**; dev/test: Standard | Premium: zone redundant, isolated capacity, predictable latency; 14-day TTL so consumers survive long outages; max delivery 10 → DLQ | `disableLocalAuth` (no SAS keys); prod private endpoint; data roles per entity | Premium is the largest line item (see below); Standard in dev/test |
| **Azure SQL Database** | Idempotency via unique keys, ordering via optimistic concurrency, FR7 lookups via indexes, `sp_getapplock` for the sync leader. See ADR-0002. | **Cosmos DB**: uniqueness and transactions only within a partition, lookups by several ids need extra containers. **Table Storage**: no secondary indexes. | General Purpose **serverless** Gen5, 0.5–4 vCores (prod, no auto-pause), 0.5–2 with auto-pause (dev/test) | Zone redundant + ZRS backups in prod, PITR 14 days, 99.99% SLA | Entra-only auth, private endpoint, TLS 1.2, auditing to Log Analytics, per-table grants | Serverless bills per vCore-second; the load is low and bursty |
| **Azure Key Vault** | Webhook secrets (several valid at once for rotation) and the Salesforce client secret (NFR6). | App settings / Container Apps secrets: no rotation workflow, no audit of reads, secret per app. | Standard | Soft delete + purge protection; apps cache secrets 5 min so a short Key Vault outage does not stop the intake | RBAC, Secrets User only to the two identities that need it, private endpoint, audit logs | Cents per 10k operations |
| **Application Insights** (on the platform Log Analytics workspace) | End-to-end traces with the correlation id, custom metrics for freshness and outcomes per country, log-based alerts. Workspace already exists at platform level. | Separate workspace: duplicated cost and fragmented queries. | Workspace-based, 50% request sampling in prod; custom metrics never sampled | Alerts drive on-call (OPERATIONS.md) | No personal data emitted by design; access through workspace RBAC | Pay per GB ingested |
| **Azure Monitor alerts + action group** | Metric alert on dead-lettered messages; per-country scheduled query alerts. | Third-party monitoring: not needed with the platform stack. | — | Detects INC-0412-style failures in minutes, per country | — | ≈ $0.10/metric alert, ≈ $1.50/log alert at 5 min |
| **Private endpoints + private DNS** | Keep data services off the internet (Key Vault, SQL, Service Bus in prod). | Service endpoints: still public IPs, not supported by all services. | 3 endpoints per prod environment | — | Network isolation | ≈ $7.30/endpoint/month + traffic |
| **User-assigned managed identities** | One per deployable, created before the apps so roles exist at first start; no credentials. | System-assigned: lifecycle tied to the app, roles recreated on redeploy. | — | — | Least privilege per service | Free |

Not used, deliberately: **Azure API Management** (Meridian's API Manager on the integration platform already fronts every API, as the scenario requires), **Azure Front Door / WAF** (the API Manager is the internet edge; adding a second edge doubles cost and latency).

## Why Premium Service Bus in production (the main cost decision)

Premium is ≈ $680/month against ≈ $10–20 for Standard at this volume. Standard would carry the load easily. Premium is chosen for prod because:
- **Private endpoint**: Standard only offers a public endpoint (protected by Entra ID); the scenario requires least privilege and the platform blocks public data endpoints for confidential workloads (A-2).
- **Zone redundancy and isolated capacity**: no noisy neighbours during the month-end peak, when all Meridian integrations are busiest.

If the security team accepts a public endpoint with Entra-only authentication and no personal data in messages, **Standard in prod would save ≈ $660/month (≈ 50% of the run cost)**. It is a one-parameter change (`isProd` → SKU) and a reasonable decision to revisit after the first months in production.

## Order-of-magnitude monthly cost (USD, list prices, one region)

Assumptions: prod volume 15k notifications/day plus month-end peaks (≈ 650k/month), ≈ 3 Service Bus operations and ≈ 4 SQL statements per notification, ≈ 5 KB of telemetry per notification plus platform logs; prices from the public calculator at the time of writing, no reservations or savings plans; the API Manager, Log Analytics base and the container registry are platform costs and excluded.

| Item | prod | dev | test |
|---|---|---|---|
| Service Bus | 680 (Premium 1 MU) | 10 | 10 |
| Azure SQL serverless | 300 (≈ 0.75 vCore average, always on, zone redundant) | 30 | 30 |
| Container Apps (5 min replicas, active rate) | 180 | 40 | 40 |
| Private endpoints and DNS | 25 | 25 | 25 |
| Application Insights / Log Analytics ingestion (≈ 15 GB prod) | 45 | 5 | 5 |
| Alerts (1 metric + 12 log alerts) | 20 | 20 | 20 |
| Key Vault | < 5 | < 5 | < 5 |
| **Total** | **≈ 1,250** | **≈ 135** | **≈ 135** |

Cost per notification in prod ≈ $0.002. The dominant lines are fixed (Service Bus Premium, SQL always-on), so growth to more countries is almost free until ≈ 10× the volume. Cost levers, in order: Service Bus Standard in prod (−$660), Container Apps idle pricing / fewer minimum replicas outside peak (−$50), SQL auto-pause in test (already on).
