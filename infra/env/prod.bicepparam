using '../main.bicep'

// Non-secret, per-environment values only. Placeholder ids are replaced by the platform team's real ones.
param environment = 'prod'
param vnetAddressPrefix = '10.40.8.0/22'
param logAnalyticsWorkspaceId = '/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-platform-monitoring/providers/Microsoft.OperationalInsights/workspaces/log-platform-prod'
param containerRegistryId = '/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/rg-platform-shared/providers/Microsoft.ContainerRegistry/registries/meridianacr'
param containerRegistryServer = 'meridianacr.azurecr.io'
param imageTag = readEnvironmentVariable('IMAGE_TAG', 'local')
param sqlAdminGroupObjectId = '11111111-1111-1111-1111-111111111111'
param sqlAdminGroupName = 'grp-payments-sql-admins-prod'
param onCallEmail = 'payments-oncall@meridian.example'
