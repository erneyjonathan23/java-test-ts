// Key Vault for provider webhook secrets and the Salesforce client secret.
// RBAC authorization only (no access policies), no public network access, purge protection.

param prefix string
param location string
param tags object
param privateEndpointSubnetId string
param privateDnsZoneId string
param logAnalyticsWorkspaceId string

@description('Principal ids that may READ secret values (Key Vault Secrets User). Nobody gets list/write here.')
param secretReaders array

resource vault 'Microsoft.KeyVault/vaults@2023-07-01' = {
  name: take('kv-${prefix}-${uniqueString(resourceGroup().id)}', 24)
  location: location
  tags: tags
  properties: {
    tenantId: subscription().tenantId
    sku: {
      family: 'A'
      name: 'standard'
    }
    enableRbacAuthorization: true
    enableSoftDelete: true
    softDeleteRetentionInDays: 90
    enablePurgeProtection: true
    publicNetworkAccess: 'Disabled'
    networkAcls: {
      defaultAction: 'Deny'
      bypass: 'None'
    }
  }
}

// Key Vault Secrets User: read secret values only.
var secretsUserRoleId = '4633458b-17de-408a-b874-0445c86b69e6'

resource readers 'Microsoft.Authorization/roleAssignments@2022-04-01' = [for principalId in secretReaders: {
  name: guid(vault.id, principalId, secretsUserRoleId)
  scope: vault
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', secretsUserRoleId)
    principalId: principalId
    principalType: 'ServicePrincipal'
  }
}]

resource endpoint 'Microsoft.Network/privateEndpoints@2024-01-01' = {
  name: 'pe-${vault.name}'
  location: location
  tags: tags
  properties: {
    subnet: {
      id: privateEndpointSubnetId
    }
    privateLinkServiceConnections: [
      {
        name: 'vault'
        properties: {
          privateLinkServiceId: vault.id
          groupIds: ['vault']
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
        name: 'vault'
        properties: {
          privateDnsZoneId: privateDnsZoneId
        }
      }
    ]
  }
}

// Audit every secret read (who read which secret, when).
resource diagnostics 'Microsoft.Insights/diagnosticSettings@2021-05-01-preview' = {
  name: 'to-log-analytics'
  scope: vault
  properties: {
    workspaceId: logAnalyticsWorkspaceId
    logs: [
      {
        categoryGroup: 'audit'
        enabled: true
      }
    ]
  }
}

output vaultUri string = vault.properties.vaultUri
output vaultName string = vault.name
