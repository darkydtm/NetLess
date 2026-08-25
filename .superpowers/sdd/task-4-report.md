# Task 4 Report

## Status

Implemented Task 4 relay persistence and expiry only. Unrelated worktree changes were preserved. No Task 1 or Task 3 files were modified.

## Files

- `core/database/build.gradle.kts`
  - Added the existing `:core:common` dependency for `PacketId` and `NodeId`.
- `core/database/src/main/kotlin/com/netless/database/DatabaseKeyStore.kt`
  - Added database-value protection helpers that reuse the existing key wrapper.
- `core/database/src/main/kotlin/com/netless/database/RelayStore.kt`
  - Added encrypted opaque packet storage, serialized metadata, packet-ID deduplication, acknowledgement removal, synchronized deterministic expiry, validation, and defensive byte-array copies.
- `core/database/src/test/kotlin/com/netless/database/RelayStoreTest.kt`
  - Added focused tests for opaque storage, deduplication, acknowledgement, expiry, and invalid input.

## Tests And Output

TDD red step:

```text
$ ./gradlew :core:database:test --tests com.netless.database.RelayStoreTest
/usr/bin/bash: ./gradlew: No such file or directory
exit: 127
```

The required red command could not start because this repository has no Gradle wrapper.

Focused green step:

```text
$ ./gradlew :core:database:test --tests com.netless.database.RelayStoreTest
/usr/bin/bash: ./gradlew: No such file or directory
exit: 127
```

All database tests were not run for the same reason. No APK or full Android build was attempted. `gradle` is also unavailable locally.

Static verification:

```text
$ git diff --check
git diff --check: PASS
```

## Commit

Commit: `81c5dea` (`add expiring relay store`)

## Concerns

- Kotlin and Android compilation remain unverified locally because neither `./gradlew` nor `gradle` is available. CI should run `:core:database:test`.
- The relay store currently uses an in-memory record map. It encrypts every serialized record through `DatabaseKeyStore`, but durable file/database backing is not present in the existing database module and would require a separate storage primitive.
- `markDelivered` removes the packet while retaining the deduplication expiry record, as required. The deduplication record is removed by `expire`.

## Review Fix Report

### Files

- `core/database/src/main/kotlin/com/netless/database/RelayStore.kt`: added optional file-backed persistence, injectable-clock validation, and expiry pruning before deduplication admission.
- `core/database/src/test/kotlin/com/netless/database/RelayStoreTest.kt`: added recreation persistence, expired-input, and post-expiry reinsertion tests.
- `core/protocol/src/main/kotlin/com/netless/protocol/PacketCodec.kt`: added explicit clock values to deterministic expiry validation.
- `core/protocol/src/test/kotlin/com/netless/protocol/PacketCodecTest.kt`: added deterministic codec expiry coverage.

### Tests / Output

```text
./gradlew :core:database:test --tests com.netless.database.RelayStoreTest
/usr/bin/bash: ./gradlew: No such file or directory
exit: 127

./gradlew :core:protocol:test --tests com.netless.protocol.PacketCodecTest
/usr/bin/bash: ./gradlew: No such file or directory
exit: 127

git diff --check
PASS
```

Focused Gradle tests could not run because the repository has no Gradle wrapper and no system Gradle is installed. No Android build was run.

### Commit

`a0e4ac2 fix task 3 and 4 review findings`
`d5c2578 harden relay store file loading`

### Concerns

- File-backed persistence requires the same durable `DatabaseKeyStore` wrapper/key alias on recreation; the existing Android keystore wrapper provides that durability.
- Kotlin/Android compilation remains unverified locally and should run in CI.

## Re-review Fix Report

### Changes

- `core/database/src/main/kotlin/com/netless/database/RelayStore.kt`
  - Reloads persisted state while holding a per-file OS lock plus a process lock, preventing stale multi-instance writes.
  - Enforces the persisted entry count and serialized value bounds before writing.
  - Writes to a temporary file and replaces the target atomically when supported, with a non-atomic replacement fallback.
  - Validates decrypted packet ID and expiry against the persisted key and metadata before accepting a record.
  - Hides expired records from `get`, and reloads before `get`, `expire`, `markDelivered`, and `count`.
- `core/database/src/test/kotlin/com/netless/database/RelayStoreTest.kt`
  - Added default-constructor recreation persistence, write-bound, expired-get, and metadata-mismatch coverage.
- `core/protocol/src/main/kotlin/com/netless/protocol/Codec.kt`
  - Restored the public legacy `PacketCodec` interface and kept `BinaryPacketCodec` implementing it.
- `core/protocol/src/main/kotlin/com/netless/protocol/PacketCodec.kt`
  - Renamed the new timestamp-aware codec to `VersionedPacketCodec` to avoid deleting the legacy interface name.
- `core/protocol/src/test/kotlin/com/netless/protocol/PacketCodecTest.kt`
  - Updated new-codec callers and added legacy interface assignability coverage.

### Verification

```text
$ ./gradlew :core:database:test --tests com.netless.database.RelayStoreTest
/usr/bin/bash: ./gradlew: No such file or directory
exit: 127

$ ./gradlew :core:protocol:test --tests com.netless.protocol.PacketCodecTest --tests com.netless.protocol.ProtocolContractsTest
/usr/bin/bash: ./gradlew: No such file or directory
exit: 127

$ git diff --check
PASS
```

Focused tests could not run because the repository has no Gradle wrapper and neither `gradle` nor `kotlinc` is installed locally. No Android build was run. Existing unrelated Task 1, Task 2, and Task 3 files were preserved.

## Remaining Review Blocker Fix Report

### Changes

- `RelayStore()` now uses the deterministic durable default `$user.home/.netless/relay-store.bin`; the existing explicit `storageFile = null` constructor API remains available for isolated in-memory callers and tests.
- Added a 10,000-entry persisted-file bound and a 2 MiB serialized-value bound before iteration or byte-array allocation.
- Added a 1 MiB packet bound on both writes and deserialization.
- Malformed or truncated files are ignored without partially mutating the store. Malformed encrypted records returned by `get` are removed safely.
- `expire` now reports only relay packets actually removed, excluding delivered entries whose deduplication records are retained until expiry.
- Added focused tests for default-compatible isolated construction, malformed files, oversized entry counts, oversized packets, write bounds, and accurate expiry counts.

### Verification

```text
$ git diff --check
PASS

$ ./gradlew :core:database:test --tests com.netless.database.RelayStoreTest
Could not run: no Gradle wrapper is present and no system gradle/kotlinc executable is available.
```

The focused test suite was not executable in this environment. Existing Task 1/2/3 files were not modified. The diff was self-reviewed for constructor compatibility, atomic malformed-file loading, pre-allocation bounds, and expiry semantics.

## Remaining Task 4 Findings Report

### Changes

- `core/database/src/main/kotlin/com/netless/database/RelayStore.kt`
  - Made the persistence location explicit through the existing `storageFile` constructor parameter; the default is now `null` (in-memory), so Android runtime no longer derives durable storage from `user.home`.
  - Made `count()` report deduplication entries, including retained acknowledgement tombstones, until their expiry. This matches the existing acknowledgement test and the deduplication contract.
  - Treats malformed persisted keys as invalid entries and skips them during load instead of allowing validation to escape.
- `core/database/src/test/kotlin/com/netless/database/RelayStoreTest.kt`
  - Updated recreation coverage to pass an explicit file rather than mutating `user.home`.
  - Added coverage that malformed persisted keys are ignored.

### Verification

```text
$ ./gradlew :core:database:test --tests com.netless.database.RelayStoreTest
/usr/bin/bash: ./gradlew: No such file or directory
exit: 127

$ command -v gradle
no executable found

$ command -v kotlinc
no executable found

$ git diff --check
PASS
```

Focused Kotlin tests could not run because the repository has no Gradle wrapper and neither `gradle` nor `kotlinc` is installed locally. No Android build was run. The existing encrypted opaque storage, process and file locking, atomic writes, bounds, expiry, and public `PacketCodec`/`VersionedPacketCodec` split were left intact.

### Commit

`395d81a fix remaining relay store review findings`

## Foundation Review Blocker Fix Report

- Added a 32 MiB aggregate persisted-file limit before RelayStore entry iteration or persisted-value allocation.
- Added focused oversized-file coverage and kept storage injection explicit through `storageFile`.
- Tests were unavailable because no Gradle wrapper, `gradle`, or `kotlinc` exists locally; `git diff --check` passed.
