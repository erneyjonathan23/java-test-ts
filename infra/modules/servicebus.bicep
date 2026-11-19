// Service Bus: one topic for canonical events, one subscription per consumer, dead-lettering on.
// prod: Premium (1 messaging unit) -> private endpoint, no public access, zone redundant, isolated capacity.
// dev/test: Standard (private endpoints are a Premium-only feature) -> public endpoint protected by Entra ID only.
// Shared Access Signature keys are disabled in every environment: only managed identities with data roles can connect.

param prefix string
param location string
param tags object
param isProd bool
param privateEndpointSubnetId string
param privateDnsZoneId string
param logAnalyticsWorkspaceId string
param intakePrincipalId string
param recorderPrincipalId string
param opsPrincipalId string
param financeConsumerPrincipalId string
param notificationsConsumerPrincipalId string

var topicName = 'premium-payment-events'
var recorderSubscriptionName = 'sf-recorder'

// Built-in data-plane roles.
var dataSenderRoleId = '69a216fc-b8fb-44d8-bc22-1f3c2cd27a39'
var dataReceiverRoleId = '4f6d3b9b-027b-4f4c-9142-0e5a2a2247e0'

resource namespace 'Microsoft.ServiceBus/namespaces@2022-10-01-preview' = {
  name: 'sb-${prefix}-${uniqueString(resourceGroup().id)}'
  location: location
  tags: tags
  sku: isProd ? {
    name: 'Premium'
    tier: 'Premium'
    capacity: 1
  } : {
    name: 'Standard'
    tier: 'Standard'
  }
  properties: {
    disableLocalAuth: true
    minimumTlsVersion: '1.2'
    publicNetworkAccess: isProd ? 'Disabled' : 'Enabled'
    zoneRedundant: isProd
  }
}

resource topic 'Microsoft.ServiceBus/namespaces/topics@2022-10-01-preview' = {
  parent: namespace
  name: topicName
  properties: {
    // Consumers must be able to fall behind for a long outage without losing events.
    defaultMessageTimeToLive: 'P14D'
    maxSizeInMegabytes: isProd ? 5120 : 1024
    enableBatchedOperations: true
    supportOrdering: false // ordering is resolved on state (ADR-0003), not by the broker
  }
}

// Consumers: name, principal, SQL filter. Replays carry the application property 'target'
// so that a replay for the recorder is not delivered again to Finance or Notifications.
var subscriptions = [
  {
    name: recorderSubscriptionName
    principalId: recorderPrincipalId
    filter: 'target IS NULL OR target = \'sf-recorder\''
  }
  {
    name: 'finance'
    principalId: financeConsumerPrincipalId
    filter: 'target IS NULL OR target = \'finance\''
  }
  {
    name: 'notifications'
    principalId: notificationsConsumerPrincipalId
    filter: 'target IS NULL OR target = \'notifications\''
  }
]

resource subs 'Microsoft.ServiceBus/namespaces/topics/subscriptions@2022-10-01-preview' = [for s in subscriptions: {
  parent: topic
  name: s.name
  properties: {
    lockDuration: 'PT1M'
    maxDeliveryCount: 10
    defaultMessageTimeToLive: 'P14D'
    deadLetteringOnMessageExpiration: true
    deadLetteringOnFilterEvaluationExceptions: true
    enableBatchedOperations: true
  }
}]

resource filters 'Microsoft.ServiceBus/namespaces/topics/subscriptions/rules@2022-10-01-preview' = [for (s, i) in subscriptions: {
  parent: subs[i]
  name: '$Default'
  properties: {
    filterType: 'SqlFilter'
    sqlFilter: {
      sqlExpression: s.filter
    }
  }
}]

// Least privilege: intake can only SEND to the topic; each consumer can only RECEIVE from its own subscription.
resource intakeSender 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(topic.id, intakePrincipalId, dataSenderRoleId)
  scope: topic
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', dataSenderRoleId)
    principalId: intakePrincipalId
    principalType: 'ServicePrincipal'
  }
}

// The ops service re-submits dead-lettered events (replay) and reads the recorder's DLQ.
resource opsSender 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(topic.id, opsPrincipalId, dataSenderRoleId)
  scope: topic
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', dataSenderRoleId)
    principalId: opsPrincipalId
    principalType: 'ServicePrincipal'
  }
}

resource opsDlqReader 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(subs[0].id, opsPrincipalId, dataReceiverRoleId)
  scope: subs[0]
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', dataReceiverRoleId)
    principalId: opsPrincipalId
    principalType: 'ServicePrincipal'
  }
}

resource receivers 'Microsoft.Authorization/roleAssignments@2022-04-01' = [for (s, i) in subscriptions: if (!empty(s.principalId)) {
  name: guid(subs[i].id, s.principalId, dataReceiverRoleId)
  scope: subs[i]
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', dataReceiverRoleId)
    principalId: s.principalId
    principalType: 'ServicePrincipal'
  }
}]

resource endpoint 'Microsoft.Network/privateEndpoints@2024-01-01' = if (isProd) {
  name: 'pe-${namespace.name}'
  location: location
  tags: tags
  properties: {
    subnet: {
      id: privateEndpointSubnetId
    }
    privateLinkServiceConnections: [
      {
        name: 'namespace'
        properties: {
          privateLinkServiceId: namespace.id
          groupIds: ['namespace']
        }
      }
    ]
  }
}

resource endpointDns 'Microsoft.Network/privateEndpoints/privateDnsZoneGroups@2024-01-01' = if (isProd) {
  parent: endpoint
  name: 'default'
  properties: {
    privateDnsZoneConfigs: [
      {
        name: 'servicebus'
        properties: {
          privateDnsZoneId: privateDnsZoneId
        }
      }
    ]
  }
}

resource diagnostics 'Microsoft.Insights/diagnosticSettings@2021-05-01-preview' = {
  name: 'to-log-analytics'
  scope: namespace
  properties: {
    workspaceId: logAnalyticsWorkspaceId
    logs: [
      {
        categoryGroup: 'allLogs'
        enabled: true
      }
    ]
    metrics: [
      {
        category: 'AllMetrics'
        enabled: true
      }
    ]
  }
}

output namespaceId string = namespace.id
output namespaceName string = namespace.name
output fqdn string = '${namespace.name}.servicebus.windows.net'
output topicName string = topic.name
output recorderSubscriptionName string = recorderSubscriptionName
