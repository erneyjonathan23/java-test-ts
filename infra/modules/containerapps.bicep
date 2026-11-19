// Container Apps environment (VNet-integrated, internal only) and the three deployables.
// No secrets are passed to the containers: they authenticate with their user-assigned managed identity
// (AZURE_CLIENT_ID) to Key Vault, Service Bus and Azure SQL.

param prefix string
param location string
param tags object
param isProd bool
param infrastructureSubnetId string
param logAnalyticsWorkspaceId string
@secure()
param appInsightsConnectionString string
param containerRegistryId string
param containerRegistryServer string
param imageTag string
param intakeIdentityId string
param intakeClientId string
param intakePrincipalId string
param recorderIdentityId string
param recorderClientId string
param recorderPrincipalId string
param opsIdentityId string
param opsClientId string
param opsPrincipalId string
param keyVaultUri string
param serviceBusFqdn string
param serviceBusNamespaceName string
param topicName string
param recorderSubscriptionName string
param sqlServerFqdn string
param sqlDatabaseName string

@description('Private address range of the API Manager, the only caller allowed in.')
param apiManagerCidr string

resource environment 'Microsoft.App/managedEnvironments@2025-01-01' = {
  name: 'cae-${prefix}'
  location: location
  tags: tags
  properties: {
    vnetConfiguration: {
      infrastructureSubnetId: infrastructureSubnetId
      // Internal: reachable only from the VNet (and the API Manager over private connectivity, A-2).
      internal: true
    }
    workloadProfiles: [
      {
        name: 'Consumption'
        workloadProfileType: 'Consumption'
      }
    ]
    zoneRedundant: isProd
    appLogsConfiguration: {
      destination: 'azure-monitor'
    }
  }
}

resource environmentLogs 'Microsoft.Insights/diagnosticSettings@2021-05-01-preview' = {
  name: 'to-log-analytics'
  scope: environment
  properties: {
    workspaceId: logAnalyticsWorkspaceId
    logs: [
      {
        categoryGroup: 'allLogs'
        enabled: true
      }
    ]
  }
}

// AcrPull for every app identity on the platform registry (which may live in another resource group).
module acrPull 'acr-pull.bicep' = {
  name: 'acr-pull-${prefix}'
  scope: resourceGroup(split(containerRegistryId, '/')[2], split(containerRegistryId, '/')[4])
  params: {
    registryName: last(split(containerRegistryId, '/'))
    principalIds: [intakePrincipalId, recorderPrincipalId, opsPrincipalId]
  }
}

var commonEnv = [
  { name: 'KEY_VAULT_URI', value: keyVaultUri }
  { name: 'SERVICEBUS_FQDN', value: serviceBusFqdn }
  { name: 'SERVICEBUS_TOPIC', value: topicName }
  { name: 'SQL_SERVER', value: sqlServerFqdn }
  { name: 'SQL_DATABASE', value: sqlDatabaseName }
  { name: 'APPLICATIONINSIGHTS_CONNECTION_STRING', secretRef: 'appinsights-connection-string' }
  { name: 'SPRING_PROFILES_ACTIVE', value: 'azure' }
]

var probes = [
  {
    type: 'Liveness'
    httpGet: {
      path: '/actuator/health/liveness'
      port: 8080
    }
    periodSeconds: 10
  }
  {
    type: 'Readiness'
    httpGet: {
      path: '/actuator/health/readiness'
      port: 8080
    }
    periodSeconds: 5
  }
]

var apiManagerOnly = [
  {
    name: 'api-manager'
    action: 'Allow'
    ipAddressRange: apiManagerCidr
  }
]

// Telemetry signs in with the managed identity (Application Insights local auth is off).
func telemetryAuth(clientId string) object => {
  name: 'APPLICATIONINSIGHTS_AUTHENTICATION_STRING'
  value: 'Authorization=AAD;ClientId=${clientId}'
}

resource intake 'Microsoft.App/containerApps@2025-01-01' = {
  name: 'ca-${prefix}-intake'
  location: location
  tags: tags
  identity: {
    type: 'UserAssigned'
    userAssignedIdentities: {
      '${intakeIdentityId}': {}
    }
  }
  properties: {
    environmentId: environment.id
    workloadProfileName: 'Consumption'
    configuration: {
      activeRevisionsMode: 'Multiple' // blue/green: new revision gets traffic only after smoke tests
      ingress: {
        external: false
        targetPort: 8080
        transport: 'http'
        allowInsecure: false
        ipSecurityRestrictions: apiManagerOnly
      }
      registries: [
        {
          server: containerRegistryServer
          identity: intakeIdentityId
        }
      ]
      secrets: [
        {
          name: 'appinsights-connection-string'
          value: appInsightsConnectionString
        }
      ]
    }
    template: {
      containers: [
        {
          name: 'intake'
          image: '${containerRegistryServer}/payments-intake:${imageTag}'
          resources: {
            cpu: json('0.5')
            memory: '1Gi'
          }
          env: concat(commonEnv, [{ name: 'AZURE_CLIENT_ID', value: intakeClientId }, telemetryAuth(intakeClientId)])
          probes: probes
        }
      ]
      scale: {
        // Never scale to zero: a cold start would break Andina's 5 s timeout.
        minReplicas: isProd ? 2 : 1
        maxReplicas: 10
        rules: [
          {
            name: 'http'
            http: {
              metadata: {
                concurrentRequests: '50'
              }
            }
          }
        ]
      }
    }
  }
  dependsOn: [acrPull]
}

resource recorder 'Microsoft.App/containerApps@2025-01-01' = {
  name: 'ca-${prefix}-recorder'
  location: location
  tags: tags
  identity: {
    type: 'UserAssigned'
    userAssignedIdentities: {
      '${recorderIdentityId}': {}
    }
  }
  properties: {
    environmentId: environment.id
    workloadProfileName: 'Consumption'
    configuration: {
      activeRevisionsMode: 'Single'
      registries: [
        {
          server: containerRegistryServer
          identity: recorderIdentityId
        }
      ]
      secrets: [
        {
          name: 'appinsights-connection-string'
          value: appInsightsConnectionString
        }
      ]
    }
    template: {
      containers: [
        {
          name: 'recorder'
          image: '${containerRegistryServer}/payments-recorder:${imageTag}'
          resources: {
            cpu: json('0.5')
            memory: '1Gi'
          }
          env: concat(commonEnv, [
            { name: 'AZURE_CLIENT_ID', value: recorderClientId }
            telemetryAuth(recorderClientId)
            { name: 'SERVICEBUS_SUBSCRIPTION', value: recorderSubscriptionName }
            // Salesforce sync guards (ADR-0001).
            { name: 'SALESFORCE_BATCH_SIZE', value: '200' }
            { name: 'SALESFORCE_MAX_CONCURRENCY', value: '5' }
            { name: 'SALESFORCE_DAILY_BUDGET', value: '150000' }
            { name: 'SALESFORCE_BUDGET_STOP_RATIO', value: '0.8' }
          ])
          probes: probes
        }
      ]
      scale: {
        minReplicas: isProd ? 2 : 1 // two replicas: one holds the sync lock, the other takes over
        maxReplicas: 5
        rules: [
          {
            name: 'servicebus-backlog'
            custom: {
              type: 'azure-servicebus'
              identity: recorderIdentityId // KEDA authenticates with the managed identity, no connection string
              metadata: {
                namespace: serviceBusNamespaceName
                topicName: topicName
                subscriptionName: recorderSubscriptionName
                messageCount: '200'
              }
            }
          }
        ]
      }
    }
  }
  dependsOn: [acrPull]
}

resource ops 'Microsoft.App/containerApps@2025-01-01' = {
  name: 'ca-${prefix}-ops'
  location: location
  tags: tags
  identity: {
    type: 'UserAssigned'
    userAssignedIdentities: {
      '${opsIdentityId}': {}
    }
  }
  properties: {
    environmentId: environment.id
    workloadProfileName: 'Consumption'
    configuration: {
      activeRevisionsMode: 'Single'
      ingress: {
        external: false
        targetPort: 8080
        transport: 'http'
        allowInsecure: false
        ipSecurityRestrictions: apiManagerOnly
      }
      registries: [
        {
          server: containerRegistryServer
          identity: opsIdentityId
        }
      ]
      secrets: [
        {
          name: 'appinsights-connection-string'
          value: appInsightsConnectionString
        }
      ]
    }
    template: {
      containers: [
        {
          name: 'ops'
          image: '${containerRegistryServer}/payments-ops:${imageTag}'
          resources: {
            cpu: json('0.25')
            memory: '0.5Gi'
          }
          env: concat(commonEnv, [{ name: 'AZURE_CLIENT_ID', value: opsClientId }, telemetryAuth(opsClientId)])
          probes: probes
        }
      ]
      scale: {
        minReplicas: 1
        maxReplicas: 2
      }
    }
  }
  dependsOn: [acrPull]
}

output intakeFqdn string = intake.properties.configuration.ingress.fqdn
output opsFqdn string = ops.properties.configuration.ingress.fqdn
