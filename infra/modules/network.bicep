// VNet with a subnet for the Container Apps environment and one for private endpoints,
// plus the private DNS zones the private endpoints register in.
// No default outbound access: the apps reach the internet (Salesforce) only through the NAT gateway.
// Assumption A-2: the integration platform (API Manager) reaches this VNet privately (peering/VPN managed by the platform team).

param prefix string
param location string
param tags object
param addressPrefix string // e.g. 10.40.0.0/22

// Default NSG rules: VNet traffic in, nothing from the internet.
// Extra rule: no SSH/RDP out, so a compromised app cannot move to other hosts.
var denyManagementOut = [
  {
    name: 'deny-ssh-rdp-out'
    properties: {
      priority: 4000
      direction: 'Outbound'
      access: 'Deny'
      protocol: 'Tcp'
      sourceAddressPrefix: '*'
      sourcePortRange: '*'
      destinationAddressPrefix: '*'
      destinationPortRanges: ['22', '3389']
    }
  }
]

resource appsNsg 'Microsoft.Network/networkSecurityGroups@2024-01-01' = {
  name: 'nsg-${prefix}-apps'
  location: location
  tags: tags
  properties: {
    securityRules: denyManagementOut
  }
}

resource endpointsNsg 'Microsoft.Network/networkSecurityGroups@2024-01-01' = {
  name: 'nsg-${prefix}-pe'
  location: location
  tags: tags
  properties: {
    securityRules: denyManagementOut
  }
}

// Fixed egress IP: Salesforce can allow-list it for the integration user.
resource egressIp 'Microsoft.Network/publicIPAddresses@2024-01-01' = {
  name: 'pip-${prefix}-egress'
  location: location
  tags: tags
  sku: {
    name: 'Standard'
  }
  zones: ['1', '2', '3']
  properties: {
    publicIPAllocationMethod: 'Static'
  }
}

resource natGateway 'Microsoft.Network/natGateways@2024-01-01' = {
  name: 'ng-${prefix}'
  location: location
  tags: tags
  sku: {
    name: 'Standard'
  }
  properties: {
    publicIpAddresses: [
      {
        id: egressIp.id
      }
    ]
  }
}

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
          defaultOutboundAccess: false
          networkSecurityGroup: {
            id: appsNsg.id
          }
          natGateway: {
            id: natGateway.id
          }
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
          defaultOutboundAccess: false
          networkSecurityGroup: {
            id: endpointsNsg.id
          }
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
output egressIpAddress string = egressIp.properties.ipAddress
