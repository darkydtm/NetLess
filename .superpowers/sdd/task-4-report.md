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
