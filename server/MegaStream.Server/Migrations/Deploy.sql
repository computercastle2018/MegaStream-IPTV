CREATE TABLE IF NOT EXISTS `__EFMigrationsHistory` (
    `MigrationId` varchar(150) CHARACTER SET utf8mb4 NOT NULL,
    `ProductVersion` varchar(32) CHARACTER SET utf8mb4 NOT NULL,
    CONSTRAINT `PK___EFMigrationsHistory` PRIMARY KEY (`MigrationId`)
) CHARACTER SET=utf8mb4;

START TRANSACTION;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    ALTER DATABASE CHARACTER SET utf8mb4;

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE TABLE `AspNetRoles` (
        `Id` varchar(255) CHARACTER SET utf8mb4 NOT NULL,
        `Name` varchar(256) CHARACTER SET utf8mb4 NULL,
        `NormalizedName` varchar(256) CHARACTER SET utf8mb4 NULL,
        `ConcurrencyStamp` longtext CHARACTER SET utf8mb4 NULL,
        CONSTRAINT `PK_AspNetRoles` PRIMARY KEY (`Id`)
    ) CHARACTER SET=utf8mb4;

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE TABLE `AspNetUsers` (
        `Id` varchar(255) CHARACTER SET utf8mb4 NOT NULL,
        `UserName` varchar(256) CHARACTER SET utf8mb4 NULL,
        `NormalizedUserName` varchar(256) CHARACTER SET utf8mb4 NULL,
        `Email` varchar(256) CHARACTER SET utf8mb4 NULL,
        `NormalizedEmail` varchar(256) CHARACTER SET utf8mb4 NULL,
        `EmailConfirmed` tinyint(1) NOT NULL,
        `PasswordHash` longtext CHARACTER SET utf8mb4 NULL,
        `SecurityStamp` longtext CHARACTER SET utf8mb4 NULL,
        `ConcurrencyStamp` longtext CHARACTER SET utf8mb4 NULL,
        `PhoneNumber` longtext CHARACTER SET utf8mb4 NULL,
        `PhoneNumberConfirmed` tinyint(1) NOT NULL,
        `TwoFactorEnabled` tinyint(1) NOT NULL,
        `LockoutEnd` datetime(6) NULL,
        `LockoutEnabled` tinyint(1) NOT NULL,
        `AccessFailedCount` int NOT NULL,
        CONSTRAINT `PK_AspNetUsers` PRIMARY KEY (`Id`)
    ) CHARACTER SET=utf8mb4;

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE TABLE `Licenses` (
        `Id` char(36) COLLATE ascii_general_ci NOT NULL,
        `KeyHash` varchar(64) CHARACTER SET utf8mb4 NOT NULL,
        `KeyLast4` varchar(4) CHARACTER SET utf8mb4 NOT NULL,
        `Label` varchar(128) CHARACTER SET utf8mb4 NOT NULL,
        `Status` varchar(16) CHARACTER SET utf8mb4 NOT NULL,
        `CreatedAt` datetime(6) NOT NULL,
        `UpdatedAt` datetime(6) NOT NULL,
        `ValidFrom` datetime(6) NOT NULL,
        `ValidUntil` datetime(6) NOT NULL,
        `MaxInstallations` int NOT NULL,
        `OfflineGraceDays` int NOT NULL,
        `Revision` bigint NOT NULL,
        CONSTRAINT `PK_Licenses` PRIMARY KEY (`Id`)
    ) CHARACTER SET=utf8mb4;

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE TABLE `RemoteProviderProfiles` (
        `Id` char(36) COLLATE ascii_general_ci NOT NULL,
        `DisplayName` varchar(128) CHARACTER SET utf8mb4 NOT NULL,
        `Type` varchar(32) CHARACTER SET utf8mb4 NOT NULL,
        `Status` varchar(16) CHARACTER SET utf8mb4 NOT NULL,
        `Revision` bigint NOT NULL,
        `EncryptedPayload` longblob NOT NULL,
        `KeyVersion` varchar(32) CHARACTER SET utf8mb4 NOT NULL,
        `CreatedAt` datetime(6) NOT NULL,
        `UpdatedAt` datetime(6) NOT NULL,
        `DeletedAt` datetime(6) NULL,
        CONSTRAINT `PK_RemoteProviderProfiles` PRIMARY KEY (`Id`)
    ) CHARACTER SET=utf8mb4;

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE TABLE `RemoteProviderRevisionSequence` (
        `Id` int NOT NULL,
        `Revision` bigint NOT NULL,
        CONSTRAINT `PK_RemoteProviderRevisionSequence` PRIMARY KEY (`Id`)
    ) CHARACTER SET=utf8mb4;

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE TABLE `UpdateReleases` (
        `Id` char(36) COLLATE ascii_general_ci NOT NULL,
        `VersionCode` bigint NOT NULL,
        `VersionName` varchar(64) CHARACTER SET utf8mb4 NOT NULL,
        `Channel` varchar(16) CHARACTER SET utf8mb4 NOT NULL,
        `PackageName` varchar(128) CHARACTER SET utf8mb4 NOT NULL,
        `Abi` varchar(16) CHARACTER SET utf8mb4 NOT NULL,
        `MinSdk` int NOT NULL,
        `Mandatory` tinyint(1) NOT NULL,
        `StorageKey` varchar(40) CHARACTER SET utf8mb4 NOT NULL,
        `SizeBytes` bigint NOT NULL,
        `Sha256` varchar(64) CHARACTER SET utf8mb4 NOT NULL,
        `SigningCertificateSha256` varchar(64) CHARACTER SET utf8mb4 NULL,
        `Notes` varchar(4096) CHARACTER SET utf8mb4 NULL,
        `PublishedAt` datetime(6) NULL,
        `Status` varchar(16) CHARACTER SET utf8mb4 NOT NULL,
        `Revision` bigint NOT NULL,
        CONSTRAINT `PK_UpdateReleases` PRIMARY KEY (`Id`)
    ) CHARACTER SET=utf8mb4;

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE TABLE `AspNetRoleClaims` (
        `Id` int NOT NULL AUTO_INCREMENT,
        `RoleId` varchar(255) CHARACTER SET utf8mb4 NOT NULL,
        `ClaimType` longtext CHARACTER SET utf8mb4 NULL,
        `ClaimValue` longtext CHARACTER SET utf8mb4 NULL,
        CONSTRAINT `PK_AspNetRoleClaims` PRIMARY KEY (`Id`),
        CONSTRAINT `FK_AspNetRoleClaims_AspNetRoles_RoleId` FOREIGN KEY (`RoleId`) REFERENCES `AspNetRoles` (`Id`) ON DELETE CASCADE
    ) CHARACTER SET=utf8mb4;

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE TABLE `AspNetUserClaims` (
        `Id` int NOT NULL AUTO_INCREMENT,
        `UserId` varchar(255) CHARACTER SET utf8mb4 NOT NULL,
        `ClaimType` longtext CHARACTER SET utf8mb4 NULL,
        `ClaimValue` longtext CHARACTER SET utf8mb4 NULL,
        CONSTRAINT `PK_AspNetUserClaims` PRIMARY KEY (`Id`),
        CONSTRAINT `FK_AspNetUserClaims_AspNetUsers_UserId` FOREIGN KEY (`UserId`) REFERENCES `AspNetUsers` (`Id`) ON DELETE CASCADE
    ) CHARACTER SET=utf8mb4;

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE TABLE `AspNetUserLogins` (
        `LoginProvider` varchar(255) CHARACTER SET utf8mb4 NOT NULL,
        `ProviderKey` varchar(255) CHARACTER SET utf8mb4 NOT NULL,
        `ProviderDisplayName` longtext CHARACTER SET utf8mb4 NULL,
        `UserId` varchar(255) CHARACTER SET utf8mb4 NOT NULL,
        CONSTRAINT `PK_AspNetUserLogins` PRIMARY KEY (`LoginProvider`, `ProviderKey`),
        CONSTRAINT `FK_AspNetUserLogins_AspNetUsers_UserId` FOREIGN KEY (`UserId`) REFERENCES `AspNetUsers` (`Id`) ON DELETE CASCADE
    ) CHARACTER SET=utf8mb4;

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE TABLE `AspNetUserRoles` (
        `UserId` varchar(255) CHARACTER SET utf8mb4 NOT NULL,
        `RoleId` varchar(255) CHARACTER SET utf8mb4 NOT NULL,
        CONSTRAINT `PK_AspNetUserRoles` PRIMARY KEY (`UserId`, `RoleId`),
        CONSTRAINT `FK_AspNetUserRoles_AspNetRoles_RoleId` FOREIGN KEY (`RoleId`) REFERENCES `AspNetRoles` (`Id`) ON DELETE CASCADE,
        CONSTRAINT `FK_AspNetUserRoles_AspNetUsers_UserId` FOREIGN KEY (`UserId`) REFERENCES `AspNetUsers` (`Id`) ON DELETE CASCADE
    ) CHARACTER SET=utf8mb4;

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE TABLE `AspNetUserTokens` (
        `UserId` varchar(255) CHARACTER SET utf8mb4 NOT NULL,
        `LoginProvider` varchar(255) CHARACTER SET utf8mb4 NOT NULL,
        `Name` varchar(255) CHARACTER SET utf8mb4 NOT NULL,
        `Value` longtext CHARACTER SET utf8mb4 NULL,
        CONSTRAINT `PK_AspNetUserTokens` PRIMARY KEY (`UserId`, `LoginProvider`, `Name`),
        CONSTRAINT `FK_AspNetUserTokens_AspNetUsers_UserId` FOREIGN KEY (`UserId`) REFERENCES `AspNetUsers` (`Id`) ON DELETE CASCADE
    ) CHARACTER SET=utf8mb4;

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE TABLE `Installations` (
        `Id` char(36) COLLATE ascii_general_ci NOT NULL,
        `TokenHash` varchar(64) CHARACTER SET utf8mb4 NOT NULL,
        `CredentialBinding` varchar(43) CHARACTER SET utf8mb4 NOT NULL,
        `FingerprintHash` varchar(64) CHARACTER SET utf8mb4 NOT NULL,
        `Platform` varchar(32) CHARACTER SET utf8mb4 NOT NULL,
        `AppVersion` varchar(64) CHARACTER SET utf8mb4 NOT NULL,
        `DeviceModel` varchar(128) CHARACTER SET utf8mb4 NOT NULL,
        `OsVersion` varchar(64) CHARACTER SET utf8mb4 NOT NULL,
        `Manufacturer` varchar(128) CHARACTER SET utf8mb4 NOT NULL,
        `Locale` varchar(32) CHARACTER SET utf8mb4 NOT NULL,
        `Status` varchar(16) CHARACTER SET utf8mb4 NOT NULL,
        `LicenseId` char(36) COLLATE ascii_general_ci NULL,
        `CreatedAt` datetime(6) NOT NULL,
        `LastSeenAt` datetime(6) NOT NULL,
        `ActivatedAt` datetime(6) NULL,
        `RevokedAt` datetime(6) NULL,
        `LastLeaseExpiresAt` datetime(6) NULL,
        CONSTRAINT `PK_Installations` PRIMARY KEY (`Id`),
        CONSTRAINT `FK_Installations_Licenses_LicenseId` FOREIGN KEY (`LicenseId`) REFERENCES `Licenses` (`Id`) ON DELETE RESTRICT
    ) CHARACTER SET=utf8mb4;

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE TABLE `ActivationCodes` (
        `Id` char(36) COLLATE ascii_general_ci NOT NULL,
        `CodeHash` varchar(64) CHARACTER SET utf8mb4 NOT NULL,
        `CodeLast4` varchar(4) CHARACTER SET utf8mb4 NOT NULL,
        `PollTokenHash` varchar(64) CHARACTER SET utf8mb4 NOT NULL,
        `InstallationId` char(36) COLLATE ascii_general_ci NOT NULL,
        `LicenseId` char(36) COLLATE ascii_general_ci NULL,
        `Status` varchar(16) CHARACTER SET utf8mb4 NOT NULL,
        `CreatedAt` datetime(6) NOT NULL,
        `ExpiresAt` datetime(6) NOT NULL,
        `ConsumedAt` datetime(6) NULL,
        CONSTRAINT `PK_ActivationCodes` PRIMARY KEY (`Id`),
        CONSTRAINT `FK_ActivationCodes_Installations_InstallationId` FOREIGN KEY (`InstallationId`) REFERENCES `Installations` (`Id`) ON DELETE RESTRICT,
        CONSTRAINT `FK_ActivationCodes_Licenses_LicenseId` FOREIGN KEY (`LicenseId`) REFERENCES `Licenses` (`Id`) ON DELETE RESTRICT
    ) CHARACTER SET=utf8mb4;

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE TABLE `DeviceSessions` (
        `Id` char(36) COLLATE ascii_general_ci NOT NULL,
        `InstallationId` char(36) COLLATE ascii_general_ci NOT NULL,
        `ClientSessionId` varchar(64) CHARACTER SET utf8mb4 NOT NULL,
        `AppVersion` varchar(64) CHARACTER SET utf8mb4 NOT NULL,
        `StartedAt` datetime(6) NOT NULL,
        `LastHeartbeatAt` datetime(6) NOT NULL,
        `EndedAt` datetime(6) NULL,
        `MemoryBytes` bigint NULL,
        `ExitReason` varchar(256) CHARACTER SET utf8mb4 NULL,
        CONSTRAINT `PK_DeviceSessions` PRIMARY KEY (`Id`),
        CONSTRAINT `FK_DeviceSessions_Installations_InstallationId` FOREIGN KEY (`InstallationId`) REFERENCES `Installations` (`Id`) ON DELETE RESTRICT
    ) CHARACTER SET=utf8mb4;

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE TABLE `DeviceUpdateCommands` (
        `Id` char(36) COLLATE ascii_general_ci NOT NULL,
        `InstallationId` char(36) COLLATE ascii_general_ci NOT NULL,
        `ReleaseId` char(36) COLLATE ascii_general_ci NOT NULL,
        `Status` varchar(16) CHARACTER SET utf8mb4 NOT NULL,
        `Mode` varchar(16) CHARACTER SET utf8mb4 NOT NULL,
        `CreatedAt` datetime(6) NOT NULL,
        `UpdatedAt` datetime(6) NOT NULL,
        `AckAt` datetime(6) NULL,
        `DownloadedAt` datetime(6) NULL,
        `InstallPromptedAt` datetime(6) NULL,
        `InstalledAt` datetime(6) NULL,
        `FailedAt` datetime(6) NULL,
        `ErrorCode` varchar(128) CHARACTER SET utf8mb4 NULL,
        `Revision` bigint NOT NULL,
        CONSTRAINT `PK_DeviceUpdateCommands` PRIMARY KEY (`Id`),
        CONSTRAINT `FK_DeviceUpdateCommands_Installations_InstallationId` FOREIGN KEY (`InstallationId`) REFERENCES `Installations` (`Id`) ON DELETE RESTRICT,
        CONSTRAINT `FK_DeviceUpdateCommands_UpdateReleases_ReleaseId` FOREIGN KEY (`ReleaseId`) REFERENCES `UpdateReleases` (`Id`) ON DELETE RESTRICT
    ) CHARACTER SET=utf8mb4;

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE TABLE `DeviceUpdateStates` (
        `InstallationId` char(36) COLLATE ascii_general_ci NOT NULL,
        `VersionCode` bigint NOT NULL,
        `PackageName` varchar(128) CHARACTER SET utf8mb4 NOT NULL,
        `Channel` varchar(16) CHARACTER SET utf8mb4 NOT NULL,
        `Sdk` int NOT NULL,
        `Mode` varchar(16) CHARACTER SET utf8mb4 NOT NULL,
        `Abi` varchar(16) CHARACTER SET utf8mb4 NOT NULL,
        `UpdatedAt` datetime(6) NOT NULL,
        `Revision` bigint NOT NULL,
        CONSTRAINT `PK_DeviceUpdateStates` PRIMARY KEY (`InstallationId`),
        CONSTRAINT `FK_DeviceUpdateStates_Installations_InstallationId` FOREIGN KEY (`InstallationId`) REFERENCES `Installations` (`Id`) ON DELETE RESTRICT
    ) CHARACTER SET=utf8mb4;

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE TABLE `DiagnosticEvents` (
        `Id` bigint NOT NULL AUTO_INCREMENT,
        `EventId` char(36) COLLATE ascii_general_ci NOT NULL,
        `InstallationId` char(36) COLLATE ascii_general_ci NOT NULL,
        `CreatedAt` datetime(6) NOT NULL,
        `Level` varchar(16) CHARACTER SET utf8mb4 NOT NULL,
        `Category` varchar(64) CHARACTER SET utf8mb4 NOT NULL,
        `Message` varchar(2048) CHARACTER SET utf8mb4 NOT NULL,
        `OccurredAt` datetime(6) NOT NULL,
        `SessionId` varchar(64) CHARACTER SET utf8mb4 NOT NULL,
        `StackTrace` varchar(4096) CHARACTER SET utf8mb4 NOT NULL,
        `ChannelName` varchar(128) CHARACTER SET utf8mb4 NOT NULL,
        `SourceType` varchar(32) CHARACTER SET utf8mb4 NOT NULL,
        `Container` varchar(32) CHARACTER SET utf8mb4 NOT NULL,
        `VideoCodec` varchar(32) CHARACTER SET utf8mb4 NOT NULL,
        `AudioCodec` varchar(32) CHARACTER SET utf8mb4 NOT NULL,
        `MemoryBytes` bigint NULL,
        `AppVersion` varchar(64) CHARACTER SET utf8mb4 NOT NULL,
        `DeviceVersion` varchar(64) CHARACTER SET utf8mb4 NOT NULL,
        CONSTRAINT `PK_DiagnosticEvents` PRIMARY KEY (`Id`),
        CONSTRAINT `FK_DiagnosticEvents_Installations_InstallationId` FOREIGN KEY (`InstallationId`) REFERENCES `Installations` (`Id`) ON DELETE RESTRICT
    ) CHARACTER SET=utf8mb4;

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE TABLE `RemoteProviderAssignments` (
        `Id` char(36) COLLATE ascii_general_ci NOT NULL,
        `ProfileId` char(36) COLLATE ascii_general_ci NOT NULL,
        `InstallationId` char(36) COLLATE ascii_general_ci NOT NULL,
        `Policy` varchar(16) CHARACTER SET utf8mb4 NOT NULL,
        `Enabled` tinyint(1) NOT NULL,
        `Revision` bigint NOT NULL,
        `AssignedAt` datetime(6) NOT NULL,
        `RevokedAt` datetime(6) NULL,
        CONSTRAINT `PK_RemoteProviderAssignments` PRIMARY KEY (`Id`),
        CONSTRAINT `AK_RemoteProviderAssignments_Id_InstallationId` UNIQUE (`Id`, `InstallationId`),
        CONSTRAINT `FK_RemoteProviderAssignments_Installations_InstallationId` FOREIGN KEY (`InstallationId`) REFERENCES `Installations` (`Id`) ON DELETE RESTRICT,
        CONSTRAINT `FK_RemoteProviderAssignments_RemoteProviderProfiles_ProfileId` FOREIGN KEY (`ProfileId`) REFERENCES `RemoteProviderProfiles` (`Id`) ON DELETE RESTRICT
    ) CHARACTER SET=utf8mb4;

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE TABLE `V1DiagnosticReceipts` (
        `InstallationId` char(36) COLLATE ascii_general_ci NOT NULL,
        `EventId` char(36) COLLATE ascii_general_ci NOT NULL,
        `AppSessionId` char(36) COLLATE ascii_general_ci NOT NULL,
        `Sequence` bigint NOT NULL,
        `OccurredAt` bigint NOT NULL,
        `ReceivedAt` datetime(6) NOT NULL,
        `Kind` varchar(32) CHARACTER SET utf8mb4 NOT NULL,
        `PayloadJson` longtext CHARACTER SET utf8mb4 NOT NULL,
        CONSTRAINT `PK_V1DiagnosticReceipts` PRIMARY KEY (`InstallationId`, `EventId`),
        CONSTRAINT `FK_V1DiagnosticReceipts_Installations_InstallationId` FOREIGN KEY (`InstallationId`) REFERENCES `Installations` (`Id`) ON DELETE RESTRICT
    ) CHARACTER SET=utf8mb4;

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE TABLE `V1InstallationMetadata` (
        `InstallationId` char(36) COLLATE ascii_general_ci NOT NULL,
        `RegistrationIdempotencyKey` char(36) COLLATE ascii_general_ci NOT NULL,
        `AppVersionCode` int NOT NULL,
        `AndroidApi` int NOT NULL,
        `PackageName` varchar(128) CHARACTER SET utf8mb4 NOT NULL,
        `Channel` varchar(16) CHARACTER SET utf8mb4 NOT NULL,
        `Abi` varchar(16) CHARACTER SET utf8mb4 NOT NULL,
        `ManagedDevice` tinyint(1) NOT NULL,
        CONSTRAINT `PK_V1InstallationMetadata` PRIMARY KEY (`InstallationId`),
        CONSTRAINT `FK_V1InstallationMetadata_Installations_InstallationId` FOREIGN KEY (`InstallationId`) REFERENCES `Installations` (`Id`) ON DELETE RESTRICT
    ) CHARACTER SET=utf8mb4;

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE TABLE `V1SessionStates` (
        `InstallationId` char(36) COLLATE ascii_general_ci NOT NULL,
        `AppSessionId` char(36) COLLATE ascii_general_ci NOT NULL,
        `LastSequence` bigint NOT NULL,
        `Mode` varchar(16) CHARACTER SET utf8mb4 NOT NULL,
        `LastAcceptedAt` datetime(6) NOT NULL,
        `JavaUsedBytes` bigint NULL,
        `JavaMaxBytes` bigint NULL,
        `NativeHeapBytes` bigint NULL,
        `PssBytes` bigint NULL,
        `AvailableSystemBytes` bigint NULL,
        `LowMemory` tinyint(1) NULL,
        `RecoveredExitReason` varchar(32) CHARACTER SET utf8mb4 NULL,
        `RecoveredExitEvidence` varchar(32) CHARACTER SET utf8mb4 NULL,
        `RecoveredExitAt` datetime(6) NULL,
        CONSTRAINT `PK_V1SessionStates` PRIMARY KEY (`InstallationId`, `AppSessionId`),
        CONSTRAINT `FK_V1SessionStates_Installations_InstallationId` FOREIGN KEY (`InstallationId`) REFERENCES `Installations` (`Id`) ON DELETE RESTRICT
    ) CHARACTER SET=utf8mb4;

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE TABLE `RemoteProviderReports` (
        `AssignmentId` char(36) COLLATE ascii_general_ci NOT NULL,
        `InstallationId` char(36) COLLATE ascii_general_ci NOT NULL,
        `ProfileRevision` bigint NOT NULL,
        `State` varchar(32) CHARACTER SET utf8mb4 NOT NULL,
        `LastReportedAt` datetime(6) NOT NULL,
        `SafeErrorCode` varchar(64) CHARACTER SET utf8mb4 NULL,
        `ProviderReportedExpiresAt` datetime(6) NULL,
        `ProviderReportedMaxConnections` int NULL,
        CONSTRAINT `PK_RemoteProviderReports` PRIMARY KEY (`AssignmentId`),
        CONSTRAINT `FK_RemoteProviderReports_RemoteProviderAssignments_AssignmentId~` FOREIGN KEY (`AssignmentId`, `InstallationId`) REFERENCES `RemoteProviderAssignments` (`Id`, `InstallationId`) ON DELETE RESTRICT
    ) CHARACTER SET=utf8mb4;

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    INSERT INTO `RemoteProviderRevisionSequence` (`Id`, `Revision`)
    VALUES (1, 0);

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE UNIQUE INDEX `IX_ActivationCodes_CodeHash` ON `ActivationCodes` (`CodeHash`);

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE INDEX `IX_ActivationCodes_InstallationId` ON `ActivationCodes` (`InstallationId`);

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE INDEX `IX_ActivationCodes_LicenseId` ON `ActivationCodes` (`LicenseId`);

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE INDEX `IX_ActivationCodes_Status_ExpiresAt` ON `ActivationCodes` (`Status`, `ExpiresAt`);

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE INDEX `IX_AspNetRoleClaims_RoleId` ON `AspNetRoleClaims` (`RoleId`);

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE UNIQUE INDEX `RoleNameIndex` ON `AspNetRoles` (`NormalizedName`);

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE INDEX `IX_AspNetUserClaims_UserId` ON `AspNetUserClaims` (`UserId`);

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE INDEX `IX_AspNetUserLogins_UserId` ON `AspNetUserLogins` (`UserId`);

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE INDEX `IX_AspNetUserRoles_RoleId` ON `AspNetUserRoles` (`RoleId`);

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE INDEX `EmailIndex` ON `AspNetUsers` (`NormalizedEmail`);

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE UNIQUE INDEX `UserNameIndex` ON `AspNetUsers` (`NormalizedUserName`);

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE INDEX `IX_DeviceSessions_ClientSessionId` ON `DeviceSessions` (`ClientSessionId`);

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE UNIQUE INDEX `IX_DeviceSessions_InstallationId_ClientSessionId` ON `DeviceSessions` (`InstallationId`, `ClientSessionId`);

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE INDEX `IX_DeviceSessions_LastHeartbeatAt` ON `DeviceSessions` (`LastHeartbeatAt`);

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE UNIQUE INDEX `IX_DeviceUpdateCommands_InstallationId_ReleaseId` ON `DeviceUpdateCommands` (`InstallationId`, `ReleaseId`);

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE INDEX `IX_DeviceUpdateCommands_ReleaseId` ON `DeviceUpdateCommands` (`ReleaseId`);

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE INDEX `IX_DiagnosticEvents_Category_OccurredAt` ON `DiagnosticEvents` (`Category`, `OccurredAt`);

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE INDEX `IX_DiagnosticEvents_CreatedAt` ON `DiagnosticEvents` (`CreatedAt`);

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE UNIQUE INDEX `IX_DiagnosticEvents_InstallationId_EventId` ON `DiagnosticEvents` (`InstallationId`, `EventId`);

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE INDEX `IX_DiagnosticEvents_InstallationId_OccurredAt` ON `DiagnosticEvents` (`InstallationId`, `OccurredAt`);

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE INDEX `IX_DiagnosticEvents_OccurredAt` ON `DiagnosticEvents` (`OccurredAt`);

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE INDEX `IX_Installations_FingerprintHash` ON `Installations` (`FingerprintHash`);

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE INDEX `IX_Installations_LastLeaseExpiresAt` ON `Installations` (`LastLeaseExpiresAt`);

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE INDEX `IX_Installations_LastSeenAt` ON `Installations` (`LastSeenAt`);

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE INDEX `IX_Installations_LicenseId_Status` ON `Installations` (`LicenseId`, `Status`);

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE UNIQUE INDEX `IX_Installations_TokenHash` ON `Installations` (`TokenHash`);

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE UNIQUE INDEX `IX_Licenses_KeyHash` ON `Licenses` (`KeyHash`);

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE INDEX `IX_Licenses_Status_ValidUntil` ON `Licenses` (`Status`, `ValidUntil`);

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE INDEX `IX_RemoteProviderAssignments_InstallationId_Revision` ON `RemoteProviderAssignments` (`InstallationId`, `Revision`);

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE UNIQUE INDEX `IX_RemoteProviderAssignments_ProfileId_InstallationId` ON `RemoteProviderAssignments` (`ProfileId`, `InstallationId`);

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE INDEX `IX_RemoteProviderProfiles_Revision` ON `RemoteProviderProfiles` (`Revision`);

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE UNIQUE INDEX `IX_RemoteProviderReports_AssignmentId_InstallationId` ON `RemoteProviderReports` (`AssignmentId`, `InstallationId`);

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE INDEX `IX_RemoteProviderReports_InstallationId` ON `RemoteProviderReports` (`InstallationId`);

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE UNIQUE INDEX `IX_UpdateReleases_VersionCode` ON `UpdateReleases` (`VersionCode`);

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE INDEX `IX_V1DiagnosticReceipts_ReceivedAt` ON `V1DiagnosticReceipts` (`ReceivedAt`);

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE UNIQUE INDEX `IX_V1SessionStates_AppSessionId` ON `V1SessionStates` (`AppSessionId`);

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    CREATE INDEX `IX_V1SessionStates_LastAcceptedAt` ON `V1SessionStates` (`LastAcceptedAt`);

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930041247_Initial') THEN

    INSERT INTO `__EFMigrationsHistory` (`MigrationId`, `ProductVersion`)
    VALUES ('20260930041247_Initial', '8.0.20');

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

COMMIT;

START TRANSACTION;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930042416_AddDevicePolicy') THEN

    ALTER TABLE `Installations` ADD `AllowLocalExit` tinyint(1) NOT NULL DEFAULT TRUE;

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930042416_AddDevicePolicy') THEN

    ALTER TABLE `Installations` ADD `KioskMode` varchar(16) CHARACTER SET utf8mb4 NOT NULL DEFAULT 'off';

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930042416_AddDevicePolicy') THEN

    CREATE TABLE `DevicePolicyAudits` (
        `Id` char(36) COLLATE ascii_general_ci NOT NULL,
        `InstallationId` char(36) COLLATE ascii_general_ci NOT NULL,
        `ActorId` varchar(128) CHARACTER SET utf8mb4 NOT NULL,
        `OldKioskMode` varchar(16) CHARACTER SET utf8mb4 NOT NULL,
        `NewKioskMode` varchar(16) CHARACTER SET utf8mb4 NOT NULL,
        `OldAllowLocalExit` tinyint(1) NOT NULL,
        `NewAllowLocalExit` tinyint(1) NOT NULL,
        `CreatedAt` datetime(6) NOT NULL,
        CONSTRAINT `PK_DevicePolicyAudits` PRIMARY KEY (`Id`),
        CONSTRAINT `FK_DevicePolicyAudits_Installations_InstallationId` FOREIGN KEY (`InstallationId`) REFERENCES `Installations` (`Id`) ON DELETE RESTRICT
    ) CHARACTER SET=utf8mb4;

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930042416_AddDevicePolicy') THEN

    CREATE INDEX `IX_DevicePolicyAudits_InstallationId_CreatedAt` ON `DevicePolicyAudits` (`InstallationId`, `CreatedAt`);

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930042416_AddDevicePolicy') THEN

    INSERT INTO `__EFMigrationsHistory` (`MigrationId`, `ProductVersion`)
    VALUES ('20260930042416_AddDevicePolicy', '8.0.20');

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

COMMIT;

START TRANSACTION;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930185043_AddLocalSubscriptions') THEN

    ALTER TABLE `Installations` ADD `LocalSubscriptionsJson` longtext CHARACTER SET utf8mb4 NULL;

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930185043_AddLocalSubscriptions') THEN

    ALTER TABLE `Installations` ADD `LocalSubscriptionsReportedAt` datetime(6) NULL;

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

DROP PROCEDURE IF EXISTS MigrationsScript;
DELIMITER //
CREATE PROCEDURE MigrationsScript()
BEGIN
    IF NOT EXISTS(SELECT 1 FROM `__EFMigrationsHistory` WHERE `MigrationId` = '20260930185043_AddLocalSubscriptions') THEN

    INSERT INTO `__EFMigrationsHistory` (`MigrationId`, `ProductVersion`)
    VALUES ('20260930185043_AddLocalSubscriptions', '8.0.20');

    END IF;
END //
DELIMITER ;
CALL MigrationsScript();
DROP PROCEDURE MigrationsScript;

COMMIT;

