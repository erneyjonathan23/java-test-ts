// Application Insights (workspace-based, on the platform Log Analytics workspace), the on-call action group
// and the alerts described in docs/operations/OPERATIONS.md. Alerts that depend on country are created per country.

param prefix string
param location string
param tags object
param logAnalyticsWorkspaceId string
param onCallEmail string
param countries array
param isProd bool
param serviceBusNamespaceId string

@description('Identities allowed to send telemetry (local auth is off, so they use Entra ID).')
param telemetryPublisherIds array

resource appInsights 'Microsoft.Insights/components@2020-02-02' = {
  name: 'appi-${prefix}'
  location: location
  tags: tags
  kind: 'web'
  properties: {
    Application_Type: 'web'
    WorkspaceResourceId: logAnalyticsWorkspaceId
    IngestionMode: 'LogAnalytics'
    // Telemetry is sampled for requests only; custom metrics (freshness, outcomes) are never sampled.
    SamplingPercentage: isProd ? 50 : 100
    DisableLocalAuth: true
  }
}

// Monitoring Metrics Publisher: send telemetry only.
var metricsPublisherRoleId = '3913510d-42f4-4e42-8a64-420c390055eb'

resource publishers 'Microsoft.Authorization/roleAssignments@2022-04-01' = [for principalId in telemetryPublisherIds: {
  name: guid(appInsights.id, principalId, metricsPublisherRoleId)
  scope: appInsights
  properties: {
    roleDefinitionId: subscriptionResourceId('Microsoft.Authorization/roleDefinitions', metricsPublisherRoleId)
    principalId: principalId
    principalType: 'ServicePrincipal'
  }
}]

resource onCall 'Microsoft.Insights/actionGroups@2023-01-01' = {
  name: 'ag-${prefix}-oncall'
  location: 'global'
  tags: tags
  properties: {
    groupShortName: 'pay-oncall'
    enabled: true
    emailReceivers: [
      {
        name: 'payments-oncall'
        emailAddress: onCallEmail
        useCommonAlertSchema: true
      }
    ]
  }
}

// Any dead-lettered message, on any subscription, is a paging event: it is a payment that will not reach Salesforce on its own.
resource dlqAlert 'Microsoft.Insights/metricAlerts@2018-03-01' = {
  name: 'alert-${prefix}-dlq'
  location: 'global'
  tags: tags
  properties: {
    severity: isProd ? 1 : 3
    enabled: true
    scopes: [serviceBusNamespaceId]
    evaluationFrequency: 'PT1M'
    windowSize: 'PT5M'
    autoMitigate: true // resolves itself when the DLQ is empty again
    criteria: {
      'odata.type': 'Microsoft.Azure.Monitor.SingleResourceMultipleMetricCriteria'
      allOf: [
        {
          name: 'dead-lettered'
          criterionType: 'StaticThresholdCriterion'
          metricName: 'DeadletteredMessages'
          metricNamespace: 'Microsoft.ServiceBus/namespaces'
          operator: 'GreaterThan'
          threshold: 0
          timeAggregation: 'Maximum'
          dimensions: [
            {
              name: 'EntityName'
              operator: 'Include'
              values: ['*']
            }
          ]
        }
      ]
    }
    actions: [
      {
        actionGroupId: onCall.id
      }
    ]
  }
}

// Per-country log alerts on the telemetry emitted by the services (custom metrics/events in Application Insights).
var countryAlerts = [
  {
    key: 'freshness'
    description: 'p95 freshness (provider notification -> recorded in Salesforce) above 5 minutes'
    severity: 2
    query: 'customMetrics | where name == "payments.freshness.seconds" and tostring(customDimensions.country) == "{0}" | summarize p95 = percentile(value, 95) | where p95 > 300'
  }
  {
    key: 'needs-attention'
    description: 'Payments parked as NEEDS_ATTENTION (Salesforce rejected the data, e.g. unknown policy)'
    severity: 2
    query: 'customEvents | where name == "payment.needs_attention" and tostring(customDimensions.country) == "{0}" | summarize n = count() | where n > 5'
  }
  {
    key: 'intake-rejections'
    description: 'Signature failures or unsupported notifications (secret rotation, provider change, attack)'
    severity: 2
    query: 'customMetrics | where name == "intake.rejected" and tostring(customDimensions.country) == "{0}" | summarize n = sum(valueSum) | where n > 10'
  }
  {
    key: 'traffic-drop'
    description: 'No accepted notification for 30 minutes (provider or API Manager problem); peak hours only'
    severity: 3
    query: 'customMetrics | where name == "intake.accepted" and tostring(customDimensions.country) == "{0}" | summarize n = sum(valueSum) | where n == 0'
  }
]

resource logAlerts 'Microsoft.Insights/scheduledQueryRules@2023-03-15-preview' = [for item in flatten(map(countries, c => map(countryAlerts, a => {
  country: c.code
  alert: a
}))): {
  name: 'alert-${prefix}-${toLower(item.country)}-${item.alert.key}'
  location: location
  tags: tags
  properties: {
    displayName: '[${item.country}] ${item.alert.description}'
    severity: item.alert.severity
    enabled: true
    scopes: [appInsights.id]
    evaluationFrequency: 'PT5M'
    windowSize: item.alert.key == 'traffic-drop' ? 'PT30M' : 'PT15M'
    criteria: {
      allOf: [
        {
          query: format(item.alert.query, item.country)
          timeAggregation: 'Count'
          operator: 'GreaterThan'
          threshold: 0
          failingPeriods: {
            numberOfEvaluationPeriods: 1
            minFailingPeriodsToAlert: 1
          }
        }
      ]
    }
    actions: {
      actionGroups: [onCall.id]
    }
  }
}]

output appInsightsConnectionString string = appInsights.properties.ConnectionString
output actionGroupId string = onCall.id
