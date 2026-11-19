-- Run by the CD pipeline (as the Entra admin group's workload identity) after Flyway migrations.
-- Database users for the managed identities, with the minimum permissions each service needs.
-- No SQL logins or passwords exist: the server is Entra-only.

IF NOT EXISTS (SELECT 1 FROM sys.database_principals WHERE name = '$(intakeIdentityName)')
    CREATE USER [$(intakeIdentityName)] FROM EXTERNAL PROVIDER;
IF NOT EXISTS (SELECT 1 FROM sys.database_principals WHERE name = '$(recorderIdentityName)')
    CREATE USER [$(recorderIdentityName)] FROM EXTERNAL PROVIDER;
IF NOT EXISTS (SELECT 1 FROM sys.database_principals WHERE name = '$(opsIdentityName)')
    CREATE USER [$(opsIdentityName)] FROM EXTERNAL PROVIDER;

-- Intake: claim idempotency keys and record attempts. Cannot read or change payment state.
GRANT SELECT, INSERT, UPDATE ON dbo.inbound_event   TO [$(intakeIdentityName)];
GRANT INSERT                 ON dbo.inbound_attempt TO [$(intakeIdentityName)];

-- Recorder: apply events, maintain payment state, Salesforce sync bookkeeping, leader lock.
GRANT SELECT, UPDATE         ON dbo.inbound_event   TO [$(recorderIdentityName)];
GRANT SELECT, INSERT, UPDATE ON dbo.payment_state   TO [$(recorderIdentityName)];
GRANT EXECUTE ON sys.sp_getapplock     TO [$(recorderIdentityName)];
GRANT EXECUTE ON sys.sp_releaseapplock TO [$(recorderIdentityName)];

-- Ops API: read everything for FR7; may only re-flag payments for sync after a manual fix (audited in the app).
GRANT SELECT ON dbo.inbound_event   TO [$(opsIdentityName)];
GRANT SELECT ON dbo.inbound_attempt TO [$(opsIdentityName)];
GRANT SELECT ON dbo.payment_state   TO [$(opsIdentityName)];
GRANT UPDATE (policy_number, sync_status, next_sync_at) ON dbo.payment_state TO [$(opsIdentityName)];
