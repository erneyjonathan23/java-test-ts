// Azure SQL Database for operational state (idempotency, payment state, notification status).
// Entra ID authentication only (no SQL logins, no passwords anywhere), private endpoint, TLS 1.2.
// Application users are created FROM EXTERNAL PROVIDER by infra/sql/grants.sql (run by the pipeline).

param prefix string
param location string
param tags object
param isProd bool
param privateEndpointSubnetId string
param privateDnsZoneId string
param logAnalyticsWorkspaceId string
param adminGroupObjectId string
param adminGroupName string

resource server 'Microsoft.Sql/servers@2023-08-01-preview' = {
  name: 'sql-${prefix}-${uniqueString(resourceGroup().id)}'
  location: location
  tags: tags
  properties: {
    minimalTlsVersion: '1.2'
    publicNetworkAccess: 'Disabled'
    administrators: {
      administratorType: 'ActiveDirectory'
      principalType: 'Group'
      login: adminGroupName
      sid: adminGroupObjectId
      tenantId: subscription().tenantId
      azureADOnlyAuthentication: true
    }
  }
}

resource database 'Microsoft.Sql/servers/databases@2023-08-01-preview' = {
  parent: server
  name: 'payments'
  location: location
  tags: tags
  sku: {
    // General Purpose serverless: pay per vCore-second, scales 0.5 -> max vCores.
    name: 'GP_S_Gen5'
    tier: 'GeneralPurpose'
    family: 'Gen5'
    capacity: isProd ? 4 : 2
  }
  properties: {
    minCapacity: json('0.5')
    // Auto-pause adds a ~1 min resume delay: acceptable in dev/test, never in prod (intake answers within 5 s).
    autoPauseDelay: isProd ? -1 : 60
    zoneRedundant: isProd
    requestedBackupStorageRedundancy: isProd ? 'Zone' : 'Local'
    maxSizeBytes: 34359738368 // 32 GB, ~50x the 13-month estimate
  }
}

resource shortTermRetention 'Microsoft.Sql/servers/databases/backupShortTermRetentionPolicies@2023-08-01-preview' = {
  parent: database
  name: 'default'
  properties: {
    retentionDays: isProd ? 14 : 7
  }
}

resource auditing 'Microsoft.Sql/servers/auditingSettings@2023-08-01-preview' = {
  parent: server
  name: 'default'
  properties: {
    state: 'Enabled'
    isAzureMonitorTargetEnabled: true
  }
}

resource master 'Microsoft.Sql/servers/databases@2023-08-01-preview' existing = {
  parent: server
  name: 'master'
}

resource auditDiagnostics 'Microsoft.Insights/diagnosticSettings@2021-05-01-preview' = {
  name: 'audit-to-log-analytics'
  scope: master
  properties: {
    workspaceId: logAnalyticsWorkspaceId
    logs: [
      {
        category: 'SQLSecurityAuditEvents'
        enabled: true
      }
    ]
  }
  dependsOn: [auditing]
}

resource endpoint 'Microsoft.Network/privateEndpoints@2024-01-01' = {
  name: 'pe-${server.name}'
  location: location
  tags: tags
  properties: {
    subnet: {
      id: privateEndpointSubnetId
    }
    privateLinkServiceConnections: [
      {
        name: 'sqlServer'
        properties: {
          privateLinkServiceId: server.id
          groupIds: ['sqlServer']
        }
      }
    ]
  }
}

resource endpointDns 'Microsoft.Network/privateEndpoints/privateDnsZoneGroups@2024-01-01' = {
  parent: endpoint
  name: 'default'
  properties: {
    privateDnsZoneConfigs: [
      {
        name: 'sql'
        properties: {
          privateDnsZoneId: privateDnsZoneId
        }
      }
    ]
  }
}

output serverFqdn string = server.properties.fullyQualifiedDomainName
output databaseName string = database.name
