// One user-assigned managed identity per deployable: each gets only the roles it needs.

param prefix string
param location string
param tags object

resource intake 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' = {
  name: 'id-${prefix}-intake'
  location: location
  tags: tags
}

resource recorder 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' = {
  name: 'id-${prefix}-recorder'
  location: location
  tags: tags
}

resource ops 'Microsoft.ManagedIdentity/userAssignedIdentities@2023-01-31' = {
  name: 'id-${prefix}-ops'
  location: location
  tags: tags
}

output intakeId string = intake.id
output intakeClientId string = intake.properties.clientId
output intakePrincipalId string = intake.properties.principalId
output recorderId string = recorder.id
output recorderClientId string = recorder.properties.clientId
output recorderPrincipalId string = recorder.properties.principalId
output opsId string = ops.id
output opsClientId string = ops.properties.clientId
output opsPrincipalId string = ops.properties.principalId
