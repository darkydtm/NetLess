# Task 6 Report

## Completion Update

- Implemented conversation-layer decoding of sealed incoming `ContentEnvelope` payloads into persisted unread `ChatMessage` records; malformed payloads remain on the opaque callback path.
- Added live conversation and message projections, restart persistence, contact identity mapping, and delivery-state persistence.

## Scope

Implemented persistent conversation message and contact projections over `DurableEncryptedContentStore`, with restart reconstruction, contact identity keyed by profile ID, and queued/delivery state flows. The relay boundary remains opaque: the repository accepts an optional typed `ContentEnvelope` callback and never decodes relay payload bytes.

## Changes

- Added `ConversationRepository`, `Contact`, `ChatMessage`, `ConversationSummary`, `SendPolicy`, and `MessageSender`.
- Added durable store ID enumeration for rebuilding indexes after recreation.
- Made `MessageRepository` rebuild its message index and use namespaced persistent keys.
- Added the default contact endpoint hook to `IdentityRepository`.
- Added focused repository tests for restart persistence, permanent contact identity, and delivery state.

## Verification

- `./gradlew :app:test --tests com.netless.app.ConversationRepositoryTest` could not run because this checkout has no `gradlew` wrapper.
- `git diff --check` passed.
- No APK or full Android build was run, as requested.

## Review Fixes

- Replaced cold conversation snapshots with live `StateFlow` projections for messages and summaries.
- Persisted read flags and unread counts, with synchronized `markRead` updates.
- Replaced NUL-delimited records with length-prefixed `DataOutputStream` records and skipped malformed records during startup.
- Loaded both current conversation keys and legacy `message:` keys as a compatibility bridge; `MessageRepository` is no longer wired as a second content sink.
- Routed opaque `ContentEnvelope` instances through the conversation callback without decoding relay plaintext.
- Kept queued and terminal delivery emissions, validation, sender invocation/error conversion, and synchronized in-memory updates.
- Fixed `PeerMessageRuntime` to pass `TransportType.WifiDirect` to the legacy receive callback.

## Verification

- `git diff --check` passed.
- Gradle tests remain unavailable because this checkout has no Gradle wrapper; Android/Kotlin compilation was not run.

## Limitations

- The current workspace does not expose a runnable Gradle wrapper, so Kotlin compilation and tests remain unverified locally.

## Follow-up Integration

- Conversation sends now bridge to `MeshRuntime` with the selected transport policy instead of a permanent failed stub.
- Legacy NUL-delimited records are decoded during conversation startup.
- Incoming relay payloads remain opaque until the established conversation-key envelope format is wired through the repository.
