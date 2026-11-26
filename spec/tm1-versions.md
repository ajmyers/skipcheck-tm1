# TM1 versions

<!-- Written by MetadataReleasesTest from java/tm1-client/src/model/metadata/tm1-releases.json
     and the $metadata snapshots beside it; rewrite it with TM1_UPDATE_VERSIONS_DOC=true. -->

Every Planning Analytics 2.1 release from the oldest supported, with the TM1 build it installs, the schema version its `$metadata` reports, and the document it serves, committed as `java/tm1-client/src/model/metadata/tm1-schema-<document>.xml`. Several releases can share a build, and builds can share a schema version yet serve different documents. How the clients fare on each release is in the [README](../README.md#supported-tm1-versions).

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
