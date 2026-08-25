# Task 3 Report

## Status

Implemented Task 3: stable packet and delivery contracts.

## Files

- `core/protocol/src/main/kotlin/com/netless/protocol/PacketCodec.kt`
  - Added versioned `DataInputStream`/`DataOutputStream` packet encoding.
  - Encodes forwarding fields, timestamps, encrypted content metadata, recipients, payload, and signature.
  - Rejects unsupported versions, invalid lengths, expired packets, malformed flags, invalid enum values, and trailing bytes.
- `core/protocol/src/main/kotlin/com/netless/protocol/Delivery.kt`
  - Added `DeliveryState` and serializable `DeliveryReceipt`.
- `core/protocol/src/main/kotlin/com/netless/protocol/Envelope.kt`
  - Added `createdAtEpochMillis` and `expiresAtEpochMillis` to `PacketEnvelope`.
  - Added constructor and generated `copy` validation through the data class constructor.
- `core/protocol/src/main/kotlin/com/netless/protocol/Codec.kt`
  - Renamed the legacy private codec interface to avoid colliding with the new `PacketCodec` object.
- `core/protocol/src/test/kotlin/com/netless/protocol/PacketCodecTest.kt`
  - Added round-trip, invalid-expiry, and serializability tests.

## Tests / Output

Focused command requested by the brief:

```text
./gradlew :core:protocol:test --tests com.netless.protocol.PacketCodecTest
```

Result: not runnable. The repository has no `./gradlew` script. The local Gradle distribution directory contains only an incomplete `.zip.part` download and no executable Gradle installation. No APK or full Android build was run.

Static verification:

```text
git diff --cached --check
```

Result: passed with no output.

## Commit

`55ccd9d add versioned packet delivery contracts`

Follow-up compile-safety fix: removes the unnecessary private legacy interface from `BinaryPacketCodec`.

## Concerns

- Unit tests could not be executed locally because the Gradle wrapper and usable Gradle distribution are unavailable.
- Existing legacy `BinaryPacketCodec` tests were not run for the same reason; its implementation and wire format were left unchanged.
- The new codec rejects packets whose expiry timestamp is earlier than the current system time during encode/decode, as required by the brief's expired-packet constraint.

## Review Fix Report

### Files

- `core/protocol/src/main/kotlin/com/netless/protocol/PacketCodec.kt`: added explicit `nowMillis` parameters to encode and decode.
- `core/protocol/src/test/kotlin/com/netless/protocol/PacketCodecTest.kt`: added deterministic expiry coverage.
- `core/database/src/main/kotlin/com/netless/database/RelayStore.kt`: added optional file-backed persistence, injectable-clock validation, and expiry pruning before deduplication admission.
- `core/database/src/test/kotlin/com/netless/database/RelayStoreTest.kt`: added recreation persistence, expired-input, and post-expiry reinsertion tests.

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

## Foundation Review Blocker Fix Report

- Preserved the public legacy `PacketCodec`/`BinaryPacketCodec` API and magic-prefixed wire format.
- Added explicit versioned and legacy codec boundaries, migration adapter, cross-format tests, and source-compatible versioned overloads.
- Added a 32 MiB aggregate persisted-file limit before RelayStore parsing or allocation, with focused coverage.
- Tests were unavailable because no Gradle wrapper, `gradle`, or `kotlinc` exists locally; `git diff --check` passed. Storage injection remains explicit through `storageFile`.
