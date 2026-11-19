// Premium Payment Notifications — Azure resources for one environment.
// Deployed at resource-group scope by the CD pipeline (never manually). Nothing here creates secrets:
// secret VALUES are set in Key Vault by the owners (provider portals, Salesforce team) out of band.

targetScope = 'resourceGroup'

@description('Environment name.')
@allowed(['dev', 'test', 'prod'])
param environment string

@description('Azure region (one region serves the three countries).')
param location string = resourceGroup().location

@description('Countries served. Drives per-country secrets, alerts and dashboards.')
param countries array = [
  { code: 'MX', provider: 'quetzal' }
  { code: 'CO', provider: 'andina' }
  { code: 'CL', provider: 'austral' }
]

@description('Resource id of the platform Log Analytics workspace (already exists at platform level).')
param logAnalyticsWorkspaceId string

@description('Resource id of the platform Azure Container Registry the images are pulled from.')
param containerRegistryId string

@description('Login server of that registry, e.g. meridianacr.azurecr.io')
param containerRegistryServer string

@description('Image tag to deploy (immutable, the commit SHA).')
param imageTag string

@description('Object id and name of the Entra ID group that administers the SQL server (break-glass, never an app).')
param sqlAdminGroupObjectId string
param sqlAdminGroupName string

@description('Object ids of the Finance and Notifications consumer identities (owned by those squads). Empty = not granted yet.')
param financeConsumerPrincipalId string = ''
param notificationsConsumerPrincipalId string = ''

@description('Where alerts are sent (on-call rotation webhook / e-mail of the squad).')
param onCallEmail string

@description('Address space for the VNet of this environment.')
param vnetAddressPrefix string

@description('Private address range the API Manager calls from (A-20).')
param apiManagerCidr string

var isProd = environment == 'prod'
var prefix = 'pay-${environment}'
var tags = {
  workload: 'premium-payment-notifications'
  environment: environment
  owner: 'payments-integration'
  dataClassification: 'confidential-no-pii'
}

module identities 'modules/identities.bicep' = {
  name: 'identities'
  params: {
    prefix: prefix
    location: location
    tags: tags
  }
}

module network 'modules/network.bicep' = {
  name: 'network'
  params: {
    prefix: prefix
    location: location
    tags: tags
    addressPrefix: vnetAddressPrefix
  }
}

module monitoring 'modules/monitoring.bicep' = {
  name: 'monitoring'
  params: {
    prefix: prefix
    location: location
    tags: tags
    logAnalyticsWorkspaceId: logAnalyticsWorkspaceId
    onCallEmail: onCallEmail
    countries: countries
    isProd: isProd
    serviceBusNamespaceId: serviceBus.outputs.namespaceId
    telemetryPublisherIds: [
      identities.outputs.intakePrincipalId
      identities.outputs.recorderPrincipalId
      identities.outputs.opsPrincipalId
    ]
  }
}

module keyVault 'modules/keyvault.bicep' = {
  name: 'keyvault'
  params: {
    prefix: prefix
    location: location
    tags: tags
    privateEndpointSubnetId: network.outputs.privateEndpointSubnetId
    privateDnsZoneId: network.outputs.keyVaultDnsZoneId
    logAnalyticsWorkspaceId: logAnalyticsWorkspaceId
    readerPrincipalIds: [
      identities.outputs.intakePrincipalId
      identities.outputs.recorderPrincipalId
    ]
  }
}

module serviceBus 'modules/servicebus.bicep' = {
  name: 'servicebus'
  params: {
    prefix: prefix
    location: location
    tags: tags
    isProd: isProd
    privateEndpointSubnetId: network.outputs.privateEndpointSubnetId
    privateDnsZoneId: network.outputs.serviceBusDnsZoneId
    logAnalyticsWorkspaceId: logAnalyticsWorkspaceId
    intakePrincipalId: identities.outputs.intakePrincipalId
    recorderPrincipalId: identities.outputs.recorderPrincipalId
    opsPrincipalId: identities.outputs.opsPrincipalId
    financeConsumerPrincipalId: financeConsumerPrincipalId
    notificationsConsumerPrincipalId: notificationsConsumerPrincipalId
  }
}

module sql 'modules/sql.bicep' = {
  name: 'sql'
  params: {
    prefix: prefix
    location: location
    tags: tags
    isProd: isProd
    privateEndpointSubnetId: network.outputs.privateEndpointSubnetId
    privateDnsZoneId: network.outputs.sqlDnsZoneId
    logAnalyticsWorkspaceId: logAnalyticsWorkspaceId
    adminGroupObjectId: sqlAdminGroupObjectId
    adminGroupName: sqlAdminGroupName
  }
}

module apps 'modules/containerapps.bicep' = {
  name: 'containerapps'
  params: {
    prefix: prefix
    location: location
    tags: tags
    isProd: isProd
    infrastructureSubnetId: network.outputs.appsSubnetId
    logAnalyticsWorkspaceId: logAnalyticsWorkspaceId
    appInsightsConnectionString: monitoring.outputs.appInsightsConnectionString
    containerRegistryId: containerRegistryId
    containerRegistryServer: containerRegistryServer
    imageTag: imageTag
    intakeIdentityId: identities.outputs.intakeId
    intakeClientId: identities.outputs.intakeClientId
    intakePrincipalId: identities.outputs.intakePrincipalId
    recorderIdentityId: identities.outputs.recorderId
    recorderClientId: identities.outputs.recorderClientId
    recorderPrincipalId: identities.outputs.recorderPrincipalId
    opsIdentityId: identities.outputs.opsId
    opsClientId: identities.outputs.opsClientId
    opsPrincipalId: identities.outputs.opsPrincipalId
    keyVaultUri: keyVault.outputs.vaultUri
    serviceBusFqdn: serviceBus.outputs.fqdn
    serviceBusNamespaceName: serviceBus.outputs.namespaceName
    topicName: serviceBus.outputs.topicName
    recorderSubscriptionName: serviceBus.outputs.recorderSubscriptionName
    sqlServerFqdn: sql.outputs.serverFqdn
    sqlDatabaseName: sql.outputs.databaseName
    apiManagerCidr: apiManagerCidr
  }
}

output intakeInternalFqdn string = apps.outputs.intakeFqdn
output opsInternalFqdn string = apps.outputs.opsFqdn
output keyVaultName string = keyVault.outputs.vaultName
output salesforceEgressIp string = network.outputs.egressIpAddress
output webhookSecretNames array = [for c in countries: '${c.provider}-webhook']
