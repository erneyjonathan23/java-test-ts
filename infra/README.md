# Infrastructure as code

**Tool:** Bicep (validated with Bicep CLI 0.49.2 through Azure CLI 2.93.0). **Nothing has been deployed.**

## Structure

```
infra/
├── main.bicep                 one environment, resource-group scope
├── modules/
│   ├── identities.bicep       user-assigned managed identity per deployable (intake, recorder, ops)
│   ├── network.bicep          VNet (apps subnet, private-endpoint subnet) + private DNS zones
│   ├── keyvault.bicep         Key Vault (RBAC, purge protection, private endpoint, audit logs) + Secrets User grants
│   ├── servicebus.bicep       namespace, topic, 3 subscriptions with SQL filters and dead-lettering, data-plane grants
│   ├── sql.bicep              Azure SQL server (Entra-only) + serverless database, auditing, private endpoint
│   ├── containerapps.bicep    internal Container Apps environment + intake / recorder / ops apps, KEDA scaling
│   ├── acr-pull.bicep         AcrPull on the platform registry (deployed in the registry's resource group)
│   └── monitoring.bicep       Application Insights, on-call action group, DLQ alert, per-country log alerts
├── env/
│   ├── dev.bicepparam
│   ├── test.bicepparam
│   └── prod.bicepparam
└── sql/grants.sql             database users FROM EXTERNAL PROVIDER for the identities, least privilege
```

## Parameters per environment and per country

| Parameter | dev | test | prod | Why |
|---|---|---|---|---|
| Service Bus SKU | Standard | Standard | **Premium, 1 MU** | Private endpoints and zone redundancy only exist in Premium (AZURE_SERVICES.md) |
| Service Bus network | public endpoint, Entra ID only | same | private endpoint only | |
| SQL max vCores / auto-pause | 2 / 60 min | 2 / 60 min | 4 / **never** | No cold start on the intake path in prod |
| SQL zone redundancy, backup redundancy | no / LRS | no / LRS | yes / ZRS | |
| Intake min replicas | 1 | 1 | 2 | Andina's 5 s timeout: never scale to zero |
| Recorder min replicas | 1 | 1 | 2 | Second replica takes the Salesforce sync lock if the first dies |
| Container Apps zone redundancy | no | no | yes | |
| Alert severity for DLQ | 3 | 3 | 1 (pages) | |

**Per country** (`countries` parameter, default MX/quetzal, CO/andina, CL/austral):
- one webhook secret name per provider in Key Vault (`<provider>-webhook`, output `webhookSecretNames`), and
- one set of alerts per country: freshness p95, `NEEDS_ATTENTION` count, intake rejections and traffic drop.

The shared resources (topic, subscriptions, apps) are **not** duplicated per country: the country is a property of every event, which keeps NFR9 (a fourth country = one more entry in `countries`, one adapter, one secret).

## Security choices visible in the code

- No secrets in templates or parameter files. Secret **values** (provider webhook secrets, Salesforce client secret) are set in Key Vault by their owners; templates only grant `Key Vault Secrets User` to the identities that read them.
- `disableLocalAuth` on Service Bus (no SAS keys), `azureADOnlyAuthentication` on SQL (no SQL logins), RBAC-only Key Vault.
- Data-plane roles are scoped to the smallest resource: intake = *Data Sender* on the topic; each consumer = *Data Receiver* on **its own** subscription; ops = *Sender* on the topic (replay) + *Receiver* on the recorder subscription (DLQ). SQL permissions per table in `sql/grants.sql`.
- Container Apps environment is internal; Key Vault and SQL (and Service Bus in prod) have public network access disabled.

## Validation (without deploying)

```bash
az bicep lint --file infra/main.bicep                     # 0 warnings
az bicep build --file infra/main.bicep --stdout > /dev/null
az bicep build-params --file infra/env/dev.bicepparam --stdout > /dev/null   # same for test and prod
```

All three commands pass locally for the three environments. The CI pipeline runs them on every pull request, plus PSRule for Azure (Well-Architected rules). `az deployment group what-if` needs an Azure subscription, so it is defined in the CD pipeline but was not executed.

## Assumptions and known gaps

- The Log Analytics workspace, the container registry and the private connectivity from the integration platform are platform-owned; their ids in `env/*.bicepparam` are placeholders.
- The intake, recorder and ops images and Flyway migrations are referenced, but only the intake is implemented (`practical/`).
- GitHub-hosted runners cannot reach the private endpoints: data-plane steps of the CD pipeline (migrations, grants, smoke tests) run on self-hosted runners inside the VNet.
- Budgets/cost alerts and Azure Policy assignments are expected at subscription level (platform team) and are not in this template.
