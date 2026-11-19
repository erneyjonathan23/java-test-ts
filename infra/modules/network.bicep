// VNet with a subnet for the Container Apps environment and one for private endpoints,
// plus the private DNS zones the private endpoints register in.
// Assumption A-2: the integration platform (API Manager) reaches this VNet privately (peering/VPN managed by the platform team).

param prefix string
param location string
param tags object
param addressPrefix string // e.g. 10.40.0.0/22

resource vnet 'Microsoft.Network/virtualNetworks@2024-01-01' = {
  name: 'vnet-${prefix}'
  location: location
  tags: tags
  properties: {
    addressSpace: {
      addressPrefixes: [addressPrefix]
    }
    subnets: [
      {
        name: 'snet-apps'
        properties: {
          addressPrefix: cidrSubnet(addressPrefix, 23, 0)
          delegations: [
            {
              name: 'containerapps'
              properties: {
                serviceName: 'Microsoft.App/environments'
              }
            }
          ]
        }
      }
      {
        name: 'snet-private-endpoints'
        properties: {
          addressPrefix: cidrSubnet(addressPrefix, 24, 2)
          privateEndpointNetworkPolicies: 'Enabled'
        }
      }
    ]
  }
}

var zones = [
  'privatelink.vaultcore.azure.net'
  'privatelink.servicebus.windows.net'
  #disable-next-line no-hardcoded-env-urls
  'privatelink.database.windows.net'
]

resource dnsZones 'Microsoft.Network/privateDnsZones@2020-06-01' = [for zone in zones: {
  name: zone
  location: 'global'
  tags: tags
}]

resource dnsLinks 'Microsoft.Network/privateDnsZones/virtualNetworkLinks@2020-06-01' = [for (zone, i) in zones: {
  parent: dnsZones[i]
  name: 'link-${prefix}'
  location: 'global'
  properties: {
    registrationEnabled: false
    virtualNetwork: {
      id: vnet.id
    }
  }
}]

output appsSubnetId string = vnet.properties.subnets[0].id
output privateEndpointSubnetId string = vnet.properties.subnets[1].id
output keyVaultDnsZoneId string = dnsZones[0].id
output serviceBusDnsZoneId string = dnsZones[1].id
output sqlDnsZoneId string = dnsZones[2].id
