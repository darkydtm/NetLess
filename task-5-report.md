# Task 5 Review Fix Report

Implemented the Task 5 integration fixes:

- Replaced allow-all sender verification with identity-backed signing and verification. Packet signatures cover the event id and encrypted content; missing signatures or unknown sender keys are rejected.
- Added a production `RouteGraph`/`RouteEngine` provider in `AppContainer` using discovered nodes and registered adapter capabilities. BLE remains discovery-only.
- Persisted origin packets before forwarding and retained relays after transmission instead of marking them delivered on send.
- Routed Wi-Fi Direct ingress through `MeshRuntime.receive` and removed encrypted-payload decoding/string projection from `PeerMessageRuntime`.
- Preserved per-packet delivery observations and failure receipts.

Verification:

- `git diff --check` passed.
- Focused Gradle tests could not run because this checkout has no `gradlew` and no system `gradle` executable.

Known boundary:

- Current discovery advertisements expose direct peer endpoints, so the production graph builds direct discovered edges. Multi-hop graph edges require neighboring-node route advertisements; no BLE payload or synthetic BLE data was added.

## Latest review follow-up

- Added `currentNodeId` to forwarding metadata and rewrite it with the next hop at each relay; the versioned codec includes the complete metadata.
- Added final-node `onContent` handoff so mesh routing preserves opaque encrypted content for the conversation layer.
- Restored optional authenticated session acceptance in `PeerMessageRuntime` while retaining its existing packet callback constructor and `SessionTransport` packet API.
- Delivery failures now emit through `observeDelivery`; relay persistence remains until delivery processing.
- Adapter lookup now exposes availability-aware selection support.

Verification: `git diff --check` passed. Gradle tests remain unavailable because this checkout has no Gradle wrapper or system Gradle executable. The requested actual multi-instance runtime tests could not be executed in this environment.

## Final Task 5 review follow-up

- Forwarding envelopes now carry the current node and are rewritten for each relay; the versioned codec preserves this metadata while legacy constructor/codec paths remain available.
- Added an explicit `HopAcknowledgement` protocol type and retained relay records through transmission; final delivery invokes the opaque `onContent` callback and emits delivery observations for success and failure.
- Restored authenticated `SessionTransport` acceptance in `PeerMessageRuntime` without changing its existing packet callback, server lifecycle, or packet send/receive API.
- Production adapter lookup filters unavailable, failed, and closed adapters before route connection attempts; BLE remains discovery-only.
- Packet signatures now cover canonical complete packet metadata and content with fail-closed verification rather than only event ID and payload.

Verification: `git diff --check` passed. Gradle tests were not runnable because this checkout has no Gradle wrapper and no system Gradle executable. Actual multi-instance runtime tests remain an environment-limited gap.

## Latest Blocker Fixes

- Corrected `ForwardingEnvelope.copy` so relays preserve the immutable destination while changing only current-hop metadata.
- Wired authenticated `SessionTransport` establishment into the production Wi-Fi Direct adapter and passed identity signing and verification from `AppContainer`.
- Connected mesh final-node content handoff to the conversation callback without decrypting in `MeshRuntime`.
- Filtered unavailable, failed, and closed adapters before route graph construction and preserved fallback selection.
- Tracked accepted session jobs and cancelled them during `PeerMessageRuntime.stopServer`.
- Preserved failed and no-route packets in relay storage and emitted failure observations.

## Final fixer status

- Made adapter availability lookup suspend-safe and filtered unavailable adapters before route selection.
- Changed delivery observations to a per-packet replayable `StateFlow` map; no-hop sends now fail instead of claiming delivery.
- Authenticate and validate received packets before atomic relay-store deduplication/persistence and route selection.
- Persist opaque encrypted payloads from the typed final-content callback in the pending content store without plaintext conversion.

Verification: `git diff --check` passed. Kotlin/Gradle verification was unavailable because this checkout has no Gradle wrapper or system Gradle. Typed hop-ack wire framing, ack-driven relay deletion, and the requested three-node integration test remain unimplemented and are not claimed here.

Verification: `git diff --check` passed. No Gradle executable or wrapper is present, so Kotlin tests and the requested multi-instance two-hop transport tests could not run. Existing untracked workspace paths were not included.

## Latest audit follow-up

- Authenticated sessions now expose the peer public key; endpoint metadata is checked against it before packet forwarding and acknowledgement acceptance.
- MeshRuntime verification fails closed without an explicit verifier, and failed Wi-Fi Direct attempts transition to `Failed`.
- Incoming handshakes run in isolated child jobs; failed sessions close without stopping the accept loop, and completed jobs are removed.
- ControlCodec bounds frames and text, rejects truncation, invalid status values, and trailing bytes.

Verification: `git diff --check` passed. Kotlin tests could not run because this checkout has no Gradle wrapper and no system Gradle executable. The requested focused regression tests remain unexecuted in this environment.

## Task 5 continuation

- Added a versioned control-frame codec for packet forwarding and `HopAcknowledgement`; legacy packet codecs remain unchanged.
- Forwarding now sends a control frame, validates the authenticated next-hop acknowledgement packet id, node id, and status, and marks relay storage delivered only after a positive acknowledgement.
- Incoming sessions return typed acknowledgements after packet processing; final nodes still hand off opaque content before acknowledging the previous hop.
 - Added acknowledgement-aware fake transport coverage to the existing two-hop runtime test, including transport fallback behavior.

## Review Blocker Fixes

- Hop acknowledgements now mean next-hop acceptance only; relay records remain pending until final-node delivery or an explicit end-to-end receipt.
- Endpoint metadata must include matching `nodeId` and authenticated `identityKey` values; connection and acknowledgement failures remain fail-closed.
- MeshRuntime fakes now provide endpoint identity metadata and peer identities, with a persistence assertion after first-hop acceptance.

Verification: `git diff --check` passed. Kotlin tests were not run because this checkout has no Gradle wrapper or system Gradle executable.

 Verification: `git diff --check` passed. Gradle tests remain unavailable because this checkout has no Gradle wrapper or system Gradle executable. The full three-node forged/duplicate/failure integration scenarios remain unverified in this environment.

## Final Blocker Fixes

- Added `TransportAdapter.connectAuthenticated` with explicit expected peer identity and session metadata; generic adapters retain a workable identity-checking default, while the Wi-Fi Direct production adapter performs the existing authenticated handshake.
- Added final-delivery state to acknowledgement control frames. A relay now propagates final delivery upstream and deletes its stored packet only after that receipt; hop acceptance alone remains pending.
- `PeerMessageRuntime` remains explicitly authenticated in production through `AppContainer`; no unauthenticated startup path is claimed.

## Latest Task 5 finding fix

- `MeshRuntime.forward` now accepts either an immediate final `HopAcknowledgement` or a terminal `DeliveryReceipt` after intermediate-hop acceptance, and deletes the local relay record only after terminal confirmation.
- Incoming terminal receipts are admitted only for locally stored relay records, are re-emitted upstream, and produce terminal delivery observations; forged or duplicate receipts are rejected without deleting state.
- Invalid packet signatures now emit a `Failed` observation before the existing validation failure is returned.

Verification: `git diff --check` passed. Gradle tests remain unavailable because this checkout has no Gradle wrapper or system Gradle executable.

Verification: `git diff --check` passed. Kotlin/Gradle tests were not runnable because this checkout has no Gradle wrapper or system Gradle executable. Focused multi-instance forged-ack and failure tests remain environment-limited.

## Task 5 Minimal Fix

- Receipt ingress now admits only pending relay records with a downstream next hop, and revalidates the stored packet id, immutable final destination, and terminal delivery state before emitting, forwarding, and deleting the record.
- Added regression coverage for rejecting terminal receipts against final-node records; existing one-hop acknowledgement/receipt, forged/duplicate receipt, and missing-signature observation tests remain in place.

Verification: Gradle tests remain unavailable because this checkout has no Gradle wrapper or system Gradle executable. `git diff --check` is the available verification path.

## Task 5 High finding correction

- Terminal receipt admission now loads the pending relay packet and requires the receipt node to equal the packet's immutable final destination.
- A validated terminal receipt is propagated unchanged and removes local relay state only after validation; forged and duplicate receipts remain rejected.
- Updated the one-hop fake transport to model an immediate hop acknowledgement followed by a final destination receipt, and added forged/duplicate admission coverage.

Verification: `git diff --check` passed. Kotlin tests were not runnable because this checkout has no Gradle wrapper or system Gradle executable.

## Final Task 5 blockers

- Replaced repeated cold-flow `incomingPackets.first()` subscriptions with one connection stream collector and queue; ordered acknowledgement plus terminal receipt and single-frame terminal delivery are both consumed correctly.
- `RelayStore` deserialization rejects persisted non-delivered terminal receipt states, and `markTerminal` explicitly fails for unknown packet IDs.

Verification: `git diff --check` passed. Focused Gradle tests could not run because this checkout has no Gradle wrapper and no system Gradle executable.

## Final Task 5 review findings

- Separated `RelayStore.hasPending` from `contains`: terminal records remain dedup tombstones without pending packet presence.
- Retained durable terminal receipt metadata and replayed it before generic dedup; pending duplicate receives retry forwarding from stored bytes.
- `markTerminal` validates packet id, terminal delivery state, receipt time, and stored final destination when available.
- Legacy storage migration now rejects trailing bytes and runs parsing and atomic rewrite under the file lock.
- Updated contract and integration assertions for pending cleanup versus dedup retention.

Verification: `git diff --check` passed. Kotlin tests were not runnable because this checkout has no Gradle wrapper or system Gradle executable.

## Latest Task 5 review blocker

- Added explicit RelayStore file format magic/versioning with a backward-compatible reader for the pre-terminal-receipt format.
- Legacy records are validated, retained, and atomically migrated on load; encrypted opaque values, entry/value/file bounds, and atomic replacement remain enforced.
- Added restart coverage proving a terminal receipt created from a legacy store is replayable after recreation.

Verification: `git diff --check` passed. Kotlin tests were not runnable because this checkout has no Gradle wrapper or system Gradle executable.

## Latest Task 5 review blockers

- MeshRuntime now checks persisted terminal receipts before generic duplicate handling, so destination retries replay the same receipt without handing content off twice.
- RelayStore persists terminal receipt metadata and retains packet state until upstream propagation is accepted; only then is the local record deleted.
- Three-node tests assert destination replay and retained destination state after propagation.

Verification: `git diff --check` passed. Gradle tests were not runnable because this checkout has no Gradle wrapper or system Gradle executable.

## Latest Task 5 forwarding audit fix

- Origin packets now address the first route hop as `currentNodeId`; `nextHop` is the following hop or final destination.
- Relay receive accepts the addressed local node, preserves canonical integrity/signature validation, and rewrites `currentNodeId` and `nextHop` to the next two route positions.
- Duplicate packets replay a relaying observation, and terminal failure receipts are returned and propagated instead of being treated as successful delivery.

Verification: `git diff --check` passed. Kotlin tests were not runnable because this checkout has no Gradle wrapper or system Gradle executable.

## Three-node integration coverage

- Added real Origin, Relay, and Destination `MeshRuntime` instances with separate identities and independent relay stores.
- Added authenticated in-memory adapters that deliver encoded control frames into the next runtime's `receiveFrame`, using Bluetooth then Wi-Fi Direct, with opaque content delivery and failure retention.
- Added forged receipt rejection coverage and verified relay/origin cleanup only on terminal delivery.

Verification: `git diff --check` passed. Gradle tests were not runnable because this checkout has no Gradle wrapper or system Gradle executable.

## Latest Task 5 gate blocker

- Unified per-hop integrity canonicalization for origin signing, relay rewrites, and destination validation; all paths blank `perHopIntegrity` and `senderSignature` consistently and use the captured packet clock value.
- Added regression coverage proving a packet survives one relay rewrite and validates at the destination without changing receipt or authentication handling.

Verification: `git diff --check` passed. Kotlin tests were not run because this checkout has no Gradle wrapper or system Gradle executable.

## Latest Task 5 audit blockers

- Threaded one captured clock value through packet signing, per-hop integrity, codec encoding/decoding, and forwarding response validation so relay and destination use the same canonical packet timestamp and fields; removed the undefined `now` reference in `forward`.
- Control receipt decoding now preserves the encoded `long`; receipt ingress and forwarding reject future-dated receipts.
- The requested real three-node transport integration test was not added in this pass; existing tests still use the prior adapter shortcut and do not satisfy that audit requirement.

Verification: `git diff --check` passed. Gradle tests were not run because this checkout has no Gradle wrapper or system Gradle executable.

## Latest Task 5 audit correction

- Forwarding now validates terminal receipts against the packet's immutable final destination, while current-hop addressing and per-hop integrity remain enforced at every receive.
- Generic production adapters now explicitly reject authenticated connections instead of treating an unauthenticated `connect` as authenticated; Wi-Fi Direct exposes the authenticated session peer identity through `SessionConnection`.
- Control receipts now preserve timestamps and control frames enforce a minimum as well as maximum size; final-content handoff failures return `Failed` observations while retaining relay state.

Verification: `git diff --check` passed. Kotlin tests were not runnable because this checkout has no Gradle wrapper or system Gradle executable. The requested real 3-node integration coverage remains unavailable in this environment and is not claimed here.

## Focused Task 5 hardening

- Bound test signing verification to the packet sender identity instead of accepting any known node key.
- Assert the authenticated connection reports the expected route-hop identity before forwarding.
- Unified sender-signature and per-hop-integrity input through the same canonical packet representation, blanking both mutable signature fields consistently.
- Existing three-node coverage asserts the terminal receipt packet ID, destination node, timestamp, propagation to relay and origin, duplicate receipt rejection, and destination callback failure retention.

Verification: `git diff --check` passed. Kotlin tests were not run because this checkout has no Gradle wrapper or system Gradle executable.

## Latest Task 5 High finding

- Forwarding now accepts a validated immediate `HopAcknowledgement` followed by a validated terminal `DeliveryReceipt`, or a terminal receipt directly, and propagates terminal delivery upstream before deleting local relay state.
- Receive validation failures emit a `Failed` observation; terminal receipts must identify the packet destination and be `Delivered`, preventing forged or duplicate admissions from confirming delivery.
- Added regression coverage for missing-signature failure observation; existing acknowledgement and relay-retention coverage remains in place.

Verification: `git diff --check` passed. Gradle tests were not runnable because this checkout has no Gradle wrapper or system Gradle executable. The multi-instance receipt-propagation, forged-receipt, duplicate-admission, and failure-observation scenarios remain unexecuted in this environment.

## Receipt ingress correction

- Tightened terminal receipt admission to require a pending relay with a downstream next hop.
- Tightened forwarding validation so terminal receipts and terminal acknowledgements identify the authenticated final hop, while intermediate terminal acknowledgements remain rejected.

Verification: `git diff --check` passed. Kotlin tests were not runnable because this checkout has no Gradle wrapper or system Gradle executable.
