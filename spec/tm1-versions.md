# TM1 versions

<!-- Written by MetadataReleasesTest from java/tm1-client/src/model/metadata/tm1-releases.json
     and the $metadata snapshots beside it; rewrite it with TM1_UPDATE_VERSIONS_DOC=true. -->

Every Planning Analytics release from the oldest supported, with the TM1 build it installs, the schema version its `$metadata` reports, and the document it serves, committed as `java/tm1-client/src/model/metadata/tm1-schema-<document>.xml`. Several releases can share a build, and builds can share a schema version yet serve different documents. How the clients fare on each release is in the [README](../README.md#supported-tm1-versions).

| Planning Analytics | TM1 | Schema | `$metadata` document |
|---|---|---|---|
| 2.1.2 | 11.8.02400.7 | 11.8.20 | 11.8.20 |
| 2.1.3 | 11.8.02500.3 | 11.8.20 | 11.8.20 |
| 2.1.4 | 11.8.02700.4 | 11.8.20 | 11.8.20 |
| 2.1.5 | 11.8.02700.4 | 11.8.20 | 11.8.20 |
| 2.1.6 | 11.8.02800.9 | 11.8.20 | 11.8.20 |
| 2.1.7 | 11.8.02800.9 | 11.8.20 | 11.8.20 |
| 2.1.8 | 11.8.02900.8 | 11.8.20 | 11.8.20-11.8.02900.8 |
| 2.1.9 | 11.8.02900.8 | 11.8.20 | 11.8.20-11.8.02900.8 |
| 2.1.10 | 11.8.02900.8 | 11.8.20 | 11.8.20-11.8.02900.8 |
| 2.1.11 | 11.8.03000.9 | 11.8.20 | 11.8.20-11.8.02900.8 |
| 2.1.12 | 11.8.03100.4 | 11.8.20 | 11.8.20-11.8.02900.8 |
| 2.1.13 | 11.8.03200.3 | 11.8.32 | 11.8.32 |
| 2.1.14 | 11.8.03200.3 | 11.8.32 | 11.8.32 |
| 2.1.15 | 11.8.03200.3 | 11.8.32 | 11.8.32 |
| 2.1.16 | 11.8.03200.3 | 11.8.32 | 11.8.32 |
| 2.1.17 | 11.8.03400.2 | 11.8.34 | 11.8.34 |
| 2.1.18 | 11.8.03500.4 | 11.8.35 | 11.8.35 |
| 2.1.19 | 11.8.03500.6 | 11.8.35 | 11.8.35 |
| 2.1.20 | 11.8.03500.6 | 11.8.35 | 11.8.35 |
| 2.1.21 | 11.8.03700.2 | 11.8.35 | 11.8.35 |
| 2.1.22 | 11.8.03700.2 | 11.8.35 | 11.8.35 |
| 2.1.23 | 11.8.03700.2 | 11.8.35 | 11.8.35 |
| 2.1.24 | 11.8.03900.1 | 11.8.35 | 11.8.35 |
| 2.1.25 | 11.8.04000.1 | 11.8.35 | 11.8.35 |
| 3.1.13 | 12.6.7 | 12.6.0 | 12.6.0 |

## What each document changes

Against the document before it, as the elements each defines (types, properties, navigations, operations and their parameters, enum members, entity sets and imports). A document that adds and removes nothing writes the same elements differently.

### 11.8.20-11.8.02900.8 (from Planning Analytics 2.1.8, TM1 11.8.02900.8), against 11.8.20

Nothing added or removed.

### 11.8.32 (from Planning Analytics 2.1.13, TM1 11.8.03200.3), against 11.8.20-11.8.02900.8

Added (5):

- `Action ibm.tm1.api.v1.CheckFeeders(Cube: ibm.tm1.api.v1.Cube, Tuple: ibm.tm1.api.v1.Element, Selection: ibm.tm1.api.v1.CheckFeedersSelection): Collection(tm1.FedCellDescriptor)`
- `EnumMember ibm.tm1.api.v1.CheckFeedersSelection/Both`
- `EnumMember ibm.tm1.api.v1.CheckFeedersSelection/Fed`
- `EnumMember ibm.tm1.api.v1.CheckFeedersSelection/Unfed`
- `EnumType ibm.tm1.api.v1.CheckFeedersSelection`

Removed (1):

- `Action ibm.tm1.api.v1.CheckFeeders(Cube: ibm.tm1.api.v1.Cube, Tuple: ibm.tm1.api.v1.Element): Collection(tm1.FedCellDescriptor)`

### 11.8.34 (from Planning Analytics 2.1.17, TM1 11.8.03400.2), against 11.8.32

Added (6):

- `Action ibm.tm1.api.v1.Release(Cube: ibm.tm1.api.v1.Cube)`
- `Action ibm.tm1.api.v1.Release(Dimension: ibm.tm1.api.v1.Dimension)`
- `Action ibm.tm1.api.v1.Release(Element: ibm.tm1.api.v1.Element)`
- `Action ibm.tm1.api.v1.Reserve(Cube: ibm.tm1.api.v1.Cube)`
- `Action ibm.tm1.api.v1.Reserve(Dimension: ibm.tm1.api.v1.Dimension)`
- `Action ibm.tm1.api.v1.Reserve(Element: ibm.tm1.api.v1.Element)`

### 11.8.35 (from Planning Analytics 2.1.18, TM1 11.8.03500.4), against 11.8.34

Added (23):

- `Action ibm.tm1.api.v1.ConfigureEncryptionAtRest(Settings: ibm.tm1.api.v1.EncryptionAtRestProviderSettings)`
- `Action ibm.tm1.api.v1.DisableEncryptionAtRest()`
- `Action ibm.tm1.api.v1.RotateDataEncryptionKey()`
- `ActionImport ConfigureEncryptionAtRest: ibm.tm1.api.v1.ConfigureEncryptionAtRest`
- `ActionImport DisableEncryptionAtRest: ibm.tm1.api.v1.DisableEncryptionAtRest`
- `ActionImport RotateDataEncryptionKey: ibm.tm1.api.v1.RotateDataEncryptionKey`
- `ComplexType ibm.tm1.api.v1.EncryptionAtRestProviderSettings`
- `ComplexType ibm.tm1.api.v1.EncryptionAtRestValidationReport`
- `ComplexType ibm.tm1.api.v1.KeyProtectSettings : ibm.tm1.api.v1.EncryptionAtRestProviderSettings`
- `ComplexType ibm.tm1.api.v1.LocalKeyFileProviderSettings : ibm.tm1.api.v1.EncryptionAtRestProviderSettings`
- `ComplexType ibm.tm1.api.v1.VaultSettings : ibm.tm1.api.v1.EncryptionAtRestProviderSettings`
- `Function ibm.tm1.api.v1.ValidateEncryptionAtRestSettings(Settings: ibm.tm1.api.v1.EncryptionAtRestProviderSettings): tm1.EncryptionAtRestValidationReport`
- `FunctionImport ValidateEncryptionAtRestSettings: ibm.tm1.api.v1.ValidateEncryptionAtRestSettings`
- `Property ibm.tm1.api.v1.EncryptionAtRestProviderSettings/KeyRef: Edm.String`
- `Property ibm.tm1.api.v1.EncryptionAtRestValidationReport/IsValid: Edm.Boolean`
- `Property ibm.tm1.api.v1.EncryptionAtRestValidationReport/Issues: Collection(Edm.String)`
- `Property ibm.tm1.api.v1.KeyProtectSettings/AuthEndpoint: Edm.String`
- `Property ibm.tm1.api.v1.KeyProtectSettings/AuthRef: Edm.String`
- `Property ibm.tm1.api.v1.KeyProtectSettings/InstanceID: Edm.String`
- `Property ibm.tm1.api.v1.KeyProtectSettings/ServiceEndpoint: Edm.String`
- `Property ibm.tm1.api.v1.ServerSettings/EncryptionAtRest: tm1.EncryptionAtRestProviderSettings`
- `Property ibm.tm1.api.v1.VaultSettings/AuthRef: Edm.String`
- `Property ibm.tm1.api.v1.VaultSettings/ServiceEndpoint: Edm.String`

### 12.6.0 (from Planning Analytics 3.1.13, TM1 12.6.7), against 11.8.35

Added (63):

- `Action ibm.tm1.api.v1.Cancel(Job: ibm.tm1.api.v1.Job)`
- `Action ibm.tm1.api.v1.Execute(View: ibm.tm1.api.v1.MDXView, Context: Edm.Untyped): tm1.Cellset`
- `Action ibm.tm1.api.v1.ExecuteChoreWithReturn(Chore: ibm.tm1.api.v1.Chore): tm1.ChoreExecuteResult`
- `Action ibm.tm1.api.v1.ExecuteMDX(MDX: Edm.String, Context: Edm.Untyped): tm1.Cellset`
- `Action ibm.tm1.api.v1.ExecuteWithReturn(Chore: ibm.tm1.api.v1.Chore): tm1.ChoreExecuteResult`
- `Action ibm.tm1.api.v1.FlushCollectedRuleStats(Cube: ibm.tm1.api.v1.Cube)`
- `Action ibm.tm1.api.v1.GitDeploy(URL: Edm.String, Username: Edm.String, Password: Edm.String, PublicKey: Edm.String, PrivateKey: Edm.String, Passphrase: Edm.String, Branch: Edm.String, Deployment: Edm.String, Force: Edm.Boolean)`
- `Action ibm.tm1.api.v1.GitInit(URL: Edm.String, Username: Edm.String, Password: Edm.String, PublicKey: Edm.String, PrivateKey: Edm.String, Passphrase: Edm.String, Deployment: Edm.String, Force: Edm.Boolean): tm1.Git`
- `Action ibm.tm1.api.v1.StartCollectingRuleStats(Cube: ibm.tm1.api.v1.Cube)`
- `Action ibm.tm1.api.v1.StopCollectingRuleStats(Cube: ibm.tm1.api.v1.Cube)`
- `ActionImport ExecuteChoreWithReturn: ibm.tm1.api.v1.ExecuteChoreWithReturn`
- `ComplexType ibm.tm1.api.v1.ChoreExecuteResult`
- `EntitySet Jobs: ibm.tm1.api.v1.Job`
- `EntityType ibm.tm1.api.v1.Job`
- `EnumMember ibm.tm1.api.v1.ChoreExecuteStatusCode/Aborted`
- `EnumMember ibm.tm1.api.v1.ChoreExecuteStatusCode/CompletedSuccessfully`
- `EnumMember ibm.tm1.api.v1.ChoreExecuteStatusCode/CompletedWithMessages`
- `EnumMember ibm.tm1.api.v1.ChoreExecuteStatusCode/ProcessRollbackCalled`
- `EnumMember ibm.tm1.api.v1.ChoreExecuteStatusCode/QuitCalled`
- `EnumMember ibm.tm1.api.v1.ChoreExecuteStatusCode/RollbackCalled`
- `EnumMember ibm.tm1.api.v1.JobState/Canceling`
- `EnumMember ibm.tm1.api.v1.JobState/Running`
- `EnumMember ibm.tm1.api.v1.JobState/Waiting`
- `EnumType ibm.tm1.api.v1.ChoreExecuteStatusCode`
- `EnumType ibm.tm1.api.v1.JobState`
- `Function ibm.tm1.api.v1.Metrics(): Collection(tm1s.Metric)`
- `Function ibm.tm1.api.v1.TailTransactionLog(StartTime: Edm.DateTimeOffset): Edm.String`
- `FunctionImport Metrics: ibm.tm1.api.v1.Metrics`
- `Navigation ibm.tm1.api.v1.ChoreExecuteResult/ErrorLogFile: tm1.ErrorLogFile`
- `Navigation ibm.tm1.api.v1.Cube/MeasuresDimension: tm1.Dimension`
- `Navigation ibm.tm1.api.v1.Cube/TimeDimension: tm1.Dimension`
- `Navigation ibm.tm1.api.v1.Job/Replica: tm1s.DatabaseReplica`
- `Navigation ibm.tm1.api.v1.Job/Session: tm1.Session`
- `Navigation ibm.tm1.api.v1.Job/WaitingOn: Collection(tm1.Job)`
- `Property ibm.tm1.api.v1.AdministrationSettings/DefaultNumberFormat: Edm.String`
- `Property ibm.tm1.api.v1.AdministrationSettings/LegacyTransactionLog: Edm.Duration`
- `Property ibm.tm1.api.v1.ChoreExecuteResult/ChoreExecuteStatusCode: tm1.ChoreExecuteStatusCode`
- `Property ibm.tm1.api.v1.Cube/AllowPersistentHolds: Edm.Boolean`
- `Property ibm.tm1.api.v1.Cube/CalculationThresholdForStorage: Edm.Int32`
- `Property ibm.tm1.api.v1.Cube/CellSecurityDefaultValue: tm1.AccessPrivilege`
- `Property ibm.tm1.api.v1.Cube/CellSecurityMostRestrictive: Edm.Boolean`
- `Property ibm.tm1.api.v1.Cube/Locked: Edm.Boolean`
- `Property ibm.tm1.api.v1.Cube/ViewStorageMaxMemory: Edm.Int64`
- `Property ibm.tm1.api.v1.Cube/ViewStorageMinTime: Edm.Int32`
- `Property ibm.tm1.api.v1.Dimension/Locked: Edm.Boolean`
- `Property ibm.tm1.api.v1.Element/Locked: Edm.Boolean`
- `Property ibm.tm1.api.v1.ExternalDatabaseSettings/FlightServiceAuth: Edm.String`
- `Property ibm.tm1.api.v1.ExternalDatabaseSettings/FlightServiceLocation: Edm.String`
- `Property ibm.tm1.api.v1.ExternalDatabaseSettings/ODBCServiceRoot: Edm.String`
- `Property ibm.tm1.api.v1.HTTPSettings/UnauthenticatedSessionTimeout: Edm.Duration`
- `Property ibm.tm1.api.v1.Hierarchy/ComponentsSortSense: tm1.SortSense`
- `Property ibm.tm1.api.v1.Hierarchy/ComponentsSortType: tm1.SortType`
- `Property ibm.tm1.api.v1.Hierarchy/DefaultMemberName: Edm.String`
- `Property ibm.tm1.api.v1.Hierarchy/ElementsSortSense: tm1.SortSense`
- `Property ibm.tm1.api.v1.Hierarchy/ElementsSortType: tm1.SortType`
- `Property ibm.tm1.api.v1.Hierarchy/LevelNames: Collection(Edm.String)`
- `Property ibm.tm1.api.v1.Job/Description: Edm.String`
- `Property ibm.tm1.api.v1.Job/ElapsedTime: Edm.Duration`
- `Property ibm.tm1.api.v1.Job/ID: Edm.String`
- `Property ibm.tm1.api.v1.Job/State: tm1.JobState`
- `Property ibm.tm1.api.v1.Job/WaitTime: Edm.Duration`
- `Property ibm.tm1.api.v1.MemorySettings/MaximumGlobalQueryMemorySizeMB: Edm.Int64`
- `Property ibm.tm1.api.v1.MemorySettings/MaximumSingleQueryMemorySizeMB: Edm.Int64`

Removed (39):

- `Action ibm.tm1.api.v1.ConfigureEncryptionAtRest(Settings: ibm.tm1.api.v1.EncryptionAtRestProviderSettings)`
- `Action ibm.tm1.api.v1.DisableEncryptionAtRest()`
- `Action ibm.tm1.api.v1.Execute(View: ibm.tm1.api.v1.MDXView): tm1.Cellset`
- `Action ibm.tm1.api.v1.ExecuteMDX(MDX: Edm.String): tm1.Cellset`
- `Action ibm.tm1.api.v1.GitDeploy(URL: Edm.String, Username: Edm.String, Password: Edm.String, PublicKey: Edm.String, PrivateKey: Edm.String, Passphrase: Edm.String, Branch: Edm.String, Deployment: Edm.String, Force: Edm.Boolean, Config: ibm.tm1.api.v1.GitConfiguration)`
- `Action ibm.tm1.api.v1.GitInit(URL: Edm.String, Username: Edm.String, Password: Edm.String, PublicKey: Edm.String, PrivateKey: Edm.String, Passphrase: Edm.String, Deployment: Edm.String, Force: Edm.Boolean, Config: ibm.tm1.api.v1.GitConfiguration): tm1.Git`
- `Action ibm.tm1.api.v1.Release(Cube: ibm.tm1.api.v1.Cube)`
- `Action ibm.tm1.api.v1.Release(Dimension: ibm.tm1.api.v1.Dimension)`
- `Action ibm.tm1.api.v1.Release(Element: ibm.tm1.api.v1.Element)`
- `Action ibm.tm1.api.v1.Reserve(Cube: ibm.tm1.api.v1.Cube)`
- `Action ibm.tm1.api.v1.Reserve(Dimension: ibm.tm1.api.v1.Dimension)`
- `Action ibm.tm1.api.v1.Reserve(Element: ibm.tm1.api.v1.Element)`
- `Action ibm.tm1.api.v1.RotateDataEncryptionKey()`
- `ActionImport ConfigureEncryptionAtRest: ibm.tm1.api.v1.ConfigureEncryptionAtRest`
- `ActionImport DisableEncryptionAtRest: ibm.tm1.api.v1.DisableEncryptionAtRest`
- `ActionImport RotateDataEncryptionKey: ibm.tm1.api.v1.RotateDataEncryptionKey`
- `ComplexType ibm.tm1.api.v1.EncryptionAtRestProviderSettings`
- `ComplexType ibm.tm1.api.v1.EncryptionAtRestValidationReport`
- `ComplexType ibm.tm1.api.v1.GitConfiguration`
- `ComplexType ibm.tm1.api.v1.KeyProtectSettings : ibm.tm1.api.v1.EncryptionAtRestProviderSettings`
- `ComplexType ibm.tm1.api.v1.LocalKeyFileProviderSettings : ibm.tm1.api.v1.EncryptionAtRestProviderSettings`
- `ComplexType ibm.tm1.api.v1.VaultSettings : ibm.tm1.api.v1.EncryptionAtRestProviderSettings`
- `Function ibm.tm1.api.v1.TailTransactionLog(): Edm.String`
- `Function ibm.tm1.api.v1.ValidateEncryptionAtRestSettings(Settings: ibm.tm1.api.v1.EncryptionAtRestProviderSettings): tm1.EncryptionAtRestValidationReport`
- `FunctionImport ValidateEncryptionAtRestSettings: ibm.tm1.api.v1.ValidateEncryptionAtRestSettings`
- `Property ibm.tm1.api.v1.EncryptionAtRestProviderSettings/KeyRef: Edm.String`
- `Property ibm.tm1.api.v1.EncryptionAtRestValidationReport/IsValid: Edm.Boolean`
- `Property ibm.tm1.api.v1.EncryptionAtRestValidationReport/Issues: Collection(Edm.String)`
- `Property ibm.tm1.api.v1.Git/Config: tm1.GitConfiguration`
- `Property ibm.tm1.api.v1.HTTPSettings/OriginAllowList: Collection(Edm.String)`
- `Property ibm.tm1.api.v1.HTTPSettings/RequestEntityMaxSizeInKB: Edm.Int32`
- `Property ibm.tm1.api.v1.HTTPSettings/SessionMaxRequests: Edm.Int32`
- `Property ibm.tm1.api.v1.KeyProtectSettings/AuthEndpoint: Edm.String`
- `Property ibm.tm1.api.v1.KeyProtectSettings/AuthRef: Edm.String`
- `Property ibm.tm1.api.v1.KeyProtectSettings/InstanceID: Edm.String`
- `Property ibm.tm1.api.v1.KeyProtectSettings/ServiceEndpoint: Edm.String`
- `Property ibm.tm1.api.v1.ServerSettings/EncryptionAtRest: tm1.EncryptionAtRestProviderSettings`
- `Property ibm.tm1.api.v1.VaultSettings/AuthRef: Edm.String`
- `Property ibm.tm1.api.v1.VaultSettings/ServiceEndpoint: Edm.String`
