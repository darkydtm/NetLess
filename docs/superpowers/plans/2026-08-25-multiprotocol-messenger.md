# Multiprotocol Messenger Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Wi-Fi Direct-only message path with a testable multiprotocol mesh pipeline and rebuild the Compose app as a familiar messenger with simple and expert network views.

**Architecture:** Keep protocol-neutral packet and route contracts in `core`, implement route selection and relay behavior independently from Android APIs, and expose Android transports through registered adapters. The app layer owns persistence orchestration, lifecycle, ViewModels, and Compose screens; transport details never leak into chat UI.

**Tech Stack:** Kotlin, Kotlin coroutines/Flow, Android SDK 35, Jetpack Compose Material 3, AndroidX Lifecycle, existing crypto and encrypted content store modules, Kotlin test and coroutine test.

## Global Constraints

- Support automatic transport selection and explicit `Preferred` and `Strict` policies.
- Use a different transport on each route hop when the route requires it.
- Keep message content end-to-end encrypted; relay storage and routing code must not receive plaintext.
- Relay through compatible nodes by default.
- Expire undelivered relay packets after 24 hours and delete acknowledged packets.
- Keep unsupported Android transports unavailable until their data channel is implemented.
- Use Telegram-like information architecture without copying Telegram branding or proprietary assets.
- Keep the default UI understandable to non-technical users and hide expert details behind progressive disclosure.
- Do not add dependencies when existing Kotlin, Android, Compose, and project modules are sufficient.
- Do not build locally unless explicitly requested; use CI/GitHub Actions for project builds.

## File Map

Create the following focused units:

- `core/transport-api/.../TransportAdapter.kt` - protocol-neutral transport registry and data-hop contracts.
- `core/transport-api/.../TransportPolicy.kt` - automatic, preferred, and strict selection policy.
- `core/common/.../network/RouteGraph.kt` - expiring per-hop route candidates.
- `core/common/.../network/RouteEngine.kt` - policy-aware route selection.
- `core/protocol/.../PacketCodec.kt` - stable packet serialization boundary.
- `core/protocol/.../Delivery.kt` - acknowledgements and delivery states.
- `core/database/.../RelayStore.kt` - encrypted packet persistence, deduplication, acknowledgement, expiry.
- `app/.../TransportRegistry.kt` - Android adapter registration and availability snapshots.
- `app/.../MeshRuntime.kt` - packet forwarding lifecycle and relay integration.
- `app/.../ConversationRepository.kt` - persistent contacts/conversations/message projections.
- `app/.../MessengerViewModel.kt` - chat list, selected conversation, send state, route details.
- `app/.../MessengerScreens.kt` - chat list, conversation, contacts, settings, route details.
- `app/.../NetlessApp.kt` - navigation and adaptive layout composition.

Modify existing transport contracts, `PeerMessageRuntime`, `MessageRepository`, `AppContainer`, and existing tests only where required by the new boundaries. Leave unrelated identity and cryptography behavior unchanged.

---

### Task 1: Define Transport Adapter and Policy Contracts

**Files:**
- Create: `core/transport-api/src/main/kotlin/com/netless/transport/TransportAdapter.kt`
- Create: `core/transport-api/src/main/kotlin/com/netless/transport/TransportPolicy.kt`
- Modify: `core/transport-api/src/main/kotlin/com/netless/transport/TransportApi.kt`
- Test: `core/transport-api/src/test/kotlin/com/netless/transport/TransportPolicyTest.kt`

**Interfaces:**
- Produces `TransportAdapter`, `TransportHop`, `TransportPolicy`, and `TransportSelectionMode`.
- `TransportAdapter` exposes `val type: TransportType`, `val availability: Flow<TransportState>`, `suspend fun connect(endpoint: TransportEndpoint): TransportConnection`, and `fun supports(capability: DiscoveryCapability): Boolean`.
- `TransportPolicy` contains `mode`, ordered `preferences`, `strictTransport: TransportType?`, and `relayAllowed`.
- `TransportPolicy` constructors reject strict mode without a strict transport and reject blank preference entries.

- [ ] **Step 1: Write failing policy tests**

```kotlin
class TransportPolicyTest {

	@Test
	fun `preferred policy keeps ordered preferences`() {
		val policy = TransportPolicy.Preferred(listOf(TransportType.WifiDirect, TransportType.Bluetooth))
		assertEquals(listOf(TransportType.WifiDirect, TransportType.Bluetooth), policy.preferences)
	}

	@Test
	fun `strict policy exposes only selected transport`() {
		val policy = TransportPolicy.Strict(TransportType.Bluetooth)
		assertEquals(listOf(TransportType.Bluetooth), policy.preferences)
		assertTrue(policy.isStrict)
	}

	@Test
	fun `strict policy cannot be created without a transport`() {
		assertFailsWith<IllegalArgumentException> { TransportPolicy.Strict(null) }
	}
}
```

- [ ] **Step 2: Run the focused test to verify it fails**

Run: `./gradlew :core:transport-api:test --tests com.netless.transport.TransportPolicyTest`
Expected: FAIL because the new policy types do not exist.

- [ ] **Step 3: Implement the smallest contracts**

```kotlin
sealed class TransportPolicy private constructor(
	val mode: TransportSelectionMode,
	val preferences: List<TransportType>,
	val isStrict: Boolean,
	val relayAllowed: Boolean,
) {
	class Automatic(relayAllowed: Boolean = true) : TransportPolicy(TransportSelectionMode.Automatic, emptyList(), false, relayAllowed)
	class Preferred(preferences: List<TransportType>, relayAllowed: Boolean = true) : TransportPolicy(TransportSelectionMode.Preferred, preferences.distinct(), false, relayAllowed) {
		init { require(preferences.isNotEmpty()) }
	}
	class Strict(transport: TransportType?, relayAllowed: Boolean = true) : TransportPolicy(TransportSelectionMode.Strict, listOf(requireNotNull(transport)), true, relayAllowed)
}

enum class TransportSelectionMode { Automatic, Preferred, Strict }

interface TransportAdapter {
	val type: TransportType
	val availability: Flow<TransportState>
	fun supports(capability: DiscoveryCapability): Boolean
	suspend fun connect(endpoint: TransportEndpoint): TransportConnection
}
```

- [ ] **Step 4: Run focused tests**

Run: `./gradlew :core:transport-api:test --tests com.netless.transport.TransportPolicyTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/transport-api
git commit -m "add transport policy contracts"
```

### Task 2: Build Expiring Multi-Hop Route Selection

**Files:**
- Create: `core/common/src/main/kotlin/com/netless/network/RouteGraph.kt`
- Create: `core/common/src/main/kotlin/com/netless/network/RouteEngine.kt`
- Modify: `core/common/src/main/kotlin/com/netless/network/Route.kt`
- Modify: `core/common/src/main/kotlin/com/netless/network/RouteSelector.kt`
- Test: `core/common/src/test/kotlin/com/netless/network/RouteEngineTest.kt`

**Interfaces:**
- `RouteHop(nodeId: NodeId, nextNodeId: NodeId, transport: TransportType, endpoint: TransportEndpoint, metrics: RouteMetrics, expiresAtMillis: Long)`.
- `RouteGraph.routesTo(destination: NodeId, nowMillis: Long): List<Route>` builds acyclic routes using non-expired hops.
- `RouteEngine.select(destination: NodeId, graph: RouteGraph, policy: TransportPolicy, nowMillis: Long): Route?` returns a route whose every hop satisfies the policy.
- Automatic mode uses existing `RouteSelector` balanced scoring; preferred mode orders routes by the first matching preferred transport, then balanced scoring; strict mode rejects any route containing another transport.

- [ ] **Step 1: Write tests for mixed transports, expiry, preferred, and strict behavior**

```kotlin
@Test
fun `selects a route containing different transports per hop`() {
	val route = engine.select(target, graphOf(
		hop("a", "b", TransportType.Bluetooth),
		hop("b", "target", TransportType.WifiDirect),
	), TransportPolicy.Automatic(), 100L)
	assertEquals(listOf(TransportType.Bluetooth, TransportType.WifiDirect), route!!.hops.map { it.transport })
}

@Test
fun `strict policy rejects mixed route`() {
	assertNull(engine.select(target, graph, TransportPolicy.Strict(TransportType.WifiDirect), 100L))
}

@Test
fun `expired hop is excluded`() {
	assertNull(engine.select(target, graphOf(hop("a", "target", TransportType.WifiDirect, expiresAt = 99L)), TransportPolicy.Automatic(), 100L))
}
```

- [ ] **Step 2: Run the focused test and confirm failure**

Run: `./gradlew :core:common:test --tests com.netless.network.RouteEngineTest`
Expected: FAIL because `RouteGraph` and `RouteEngine` are missing.

- [ ] **Step 3: Implement graph traversal and policy filtering**

Use breadth-first traversal with a visited-node set, cap paths at the existing `TransferPolicy.maxHops`, and add a transport-hop list to `Route` so each selected route retains its per-hop transport. Keep deterministic ordering by sorting equal candidates by node ID. Do not add a second scoring algorithm; delegate metric ranking to `RouteSelector` after policy filtering.

- [ ] **Step 4: Run all common network tests**

Run: `./gradlew :core:common:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/common
git commit -m "add multiprotocol route engine"
```

### Task 3: Add Stable Packet and Delivery Contracts

**Files:**
- Create: `core/protocol/src/main/kotlin/com/netless/protocol/PacketCodec.kt`
- Create: `core/protocol/src/main/kotlin/com/netless/protocol/Delivery.kt`
- Modify: `core/protocol/src/main/kotlin/com/netless/protocol/Envelope.kt`
- Test: `core/protocol/src/test/kotlin/com/netless/protocol/PacketCodecTest.kt`

**Interfaces:**
- `PacketEnvelope` remains the protocol-neutral packet model and gains `createdAtEpochMillis` and `expiresAtEpochMillis` validation through its constructor and `copy` method.
- `PacketCodec.encode(packet): ByteArray` and `PacketCodec.decode(bytes): PacketEnvelope` provide a versioned binary boundary using `DataInputStream/DataOutputStream`.
- `DeliveryState` values are `Queued`, `Relaying`, `Delivered`, `Expired`, and `Failed`.
- `DeliveryReceipt(packetId: PacketId, state: DeliveryState, nodeId: NodeId, timestampEpochMillis: Long)` is serializable.

- [ ] **Step 1: Add round-trip and invalid-expiry tests**

```kotlin
@Test
fun `packet codec preserves forwarding and encrypted content`() {
	val decoded = PacketCodec.decode(PacketCodec.encode(packet))
	assertEquals(packet, decoded)
}

@Test
fun `packet rejects expiry before creation`() {
	assertFailsWith<IllegalArgumentException> {
		PacketEnvelope(forwarding, content, createdAtEpochMillis = 20L, expiresAtEpochMillis = 19L)
	}
}
```

- [ ] **Step 2: Run focused protocol tests and confirm failure**

Run: `./gradlew :core:protocol:test --tests com.netless.protocol.PacketCodecTest`
Expected: FAIL because the codec and delivery types are missing.

- [ ] **Step 3: Implement versioned encoding**

Encode the packet version first, then forwarding scalar fields, byte-array lengths, content scalar fields, recipient count, recipients, payload length, and signature length. Reject negative lengths, unsupported versions, expired packets, and trailing malformed data. Never encode plaintext message bodies in this module.

- [ ] **Step 4: Run all protocol tests**

Run: `./gradlew :core:protocol:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/protocol
git commit -m "add versioned packet delivery contracts"
```

### Task 4: Implement Relay Persistence and Expiry

**Files:**
- Create: `core/database/src/main/kotlin/com/netless/database/RelayStore.kt`
- Modify: `core/database/src/main/kotlin/com/netless/database/DatabaseKeyStore.kt`
- Test: `core/database/src/test/kotlin/com/netless/database/RelayStoreTest.kt`

**Interfaces:**
- `RelayStore.put(packet: ByteArray, packetId: PacketId, expiresAtMillis: Long, nextHop: NodeId?)` persists opaque encrypted bytes.
- `RelayStore.get(packetId: PacketId): StoredRelayPacket?` returns the opaque packet and metadata.
- `RelayStore.markDelivered(packetId: PacketId)` deletes the packet and deduplication record remains until expiry.
- `RelayStore.expire(nowMillis: Long): Int` deletes expired packets.
- `StoredRelayPacket` contains packet ID, opaque bytes, expiry, next hop, and state.

- [ ] **Step 1: Write tests for opaque storage, deduplication, acknowledgement, and 24-hour expiry**

```kotlin
@Test
fun `duplicate packet does not create a second relay entry`() {
	store.put(bytes, id, 86_400_000L, nextHop)
	store.put(bytes, id, 86_400_000L, nextHop)
	assertEquals(1, store.count())
}

@Test
fun `acknowledgement removes packet`() {
	store.put(bytes, id, 86_400_000L, nextHop)
	store.markDelivered(id)
	assertNull(store.get(id))
}
```

- [ ] **Step 2: Run focused database tests and confirm failure**

Run: `./gradlew :core:database:test --tests com.netless.database.RelayStoreTest`
Expected: FAIL because `RelayStore` is missing.

- [ ] **Step 3: Implement storage using the existing encrypted database primitives**

Store serialized metadata and packet bytes under a stable packet key, reject blank/expired input, make writes idempotent by packet ID, and make `expire` synchronized and deterministic. Keep packet bytes opaque to the store API.

- [ ] **Step 4: Run all database tests**

Run: `./gradlew :core:database:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/database
git commit -m "add expiring relay store"
```

### Task 5: Build Mesh Runtime and Migrate Wi-Fi Direct

**Files:**
- Create: `app/src/main/kotlin/com/netless/app/TransportRegistry.kt`
- Create: `app/src/main/kotlin/com/netless/app/MeshRuntime.kt`
- Modify: `app/src/main/kotlin/com/netless/app/PeerMessageRuntime.kt`
- Modify: `app/src/main/kotlin/com/netless/app/RuntimeController.kt`
- Modify: `app/src/main/kotlin/com/netless/app/NetlessApplication.kt`
- Test: `app/src/test/kotlin/com/netless/app/MeshRuntimeTest.kt`

**Interfaces:**
- `TransportRegistry.register(adapter)`, `availableAdapters()`, and `adapter(type)` expose registered adapters and state.
- `MeshRuntime.send(content: ContentEnvelope, destination: NodeId, policy: TransportPolicy): DeliveryState` creates one packet, selects a route, and forwards hop by hop.
- `MeshRuntime.receive(bytes: ByteArray, ingress: TransportType): DeliveryReceipt` validates, deduplicates, stores before forwarding, and emits delivery state.
- `MeshRuntime.observeDelivery(packetId): Flow<DeliveryState>` supports the UI.

- [ ] **Step 1: Write fake-adapter tests for mixed-hop forwarding and fallback**

```kotlin
@Test
fun `forwards one packet through bluetooth then wifi direct`() = runTest {
	val result = runtime.send(content, destination, TransportPolicy.Automatic())
	assertEquals(DeliveryState.Delivered, result)
	assertEquals(listOf(TransportType.Bluetooth, TransportType.WifiDirect), fakeNetwork.usedTransports)
}

@Test
fun `preferred transport falls back when unavailable`() = runTest {
	fakeNetwork.disable(TransportType.Bluetooth)
	assertEquals(DeliveryState.Delivered, runtime.send(content, destination, preferredPolicy))
	assertEquals(TransportType.WifiDirect, fakeNetwork.usedTransports.single())
}
```

- [ ] **Step 2: Run focused app tests and confirm failure**

Run: `./gradlew :app:test --tests com.netless.app.MeshRuntimeTest`
Expected: FAIL because the registry and runtime are missing.

- [ ] **Step 3: Implement registry and packet forwarding**

Register the existing Wi-Fi Direct data adapter first. Keep BLE discovery registration separate from BLE data availability. On receive, authenticate before relay processing, reject expired or duplicate packets, persist before send, use the route engine for the next hop, and emit a receipt. Route failure leaves the packet in relay storage until expiry.

- [ ] **Step 4: Replace `MessageFrame` usage**

Change `PeerMessageRuntime` to accept and emit encoded `PacketEnvelope` bytes. Remove plaintext `conversationId/body` framing from peer transport. Keep conversation decryption and message projection above `MeshRuntime`.

- [ ] **Step 5: Run app unit tests**

Run: `./gradlew :app:test`
Expected: PASS, including existing runtime and profile tests.

- [ ] **Step 6: Commit**

```bash
git add app core
git commit -m "migrate messaging to mesh runtime"
```

### Task 6: Add Persistent Conversations and Contacts

**Files:**
- Create: `app/src/main/kotlin/com/netless/app/ConversationRepository.kt`
- Modify: `app/src/main/kotlin/com/netless/app/MessageRepository.kt`
- Modify: `domain/identity/src/main/kotlin/com/netless/identity/IdentityRepository.kt`
- Test: `app/src/test/kotlin/com/netless/app/ConversationRepositoryTest.kt`

**Interfaces:**
- `ConversationRepository.observeConversations(): Flow<List<ConversationSummary>>`.
- `ConversationRepository.observeMessages(conversationId): Flow<List<ChatMessage>>`.
- `ConversationRepository.addContact(profileId, displayName)`.
- `ConversationRepository.send(conversationId, text, policy): Flow<DeliveryState>`.
- `ConversationSummary` contains conversation ID, contact identity, last message preview, timestamp, unread count, and delivery state.

- [ ] **Step 1: Write tests for restart-safe index, unread count, and permanent contact identity**

```kotlin
@Test
fun `messages remain discoverable after repository recreation`() {
	first.send(conversationId, "hello", automatic)
	val restored = ConversationRepository(store, mesh)
	assertEquals(listOf("hello"), restored.messages(conversationId).map { it.body })
}

@Test
fun `contact identity does not depend on endpoint`() {
	repository.addContact(profileId, "Alex")
	repository.updateEndpoint(profileId, endpoint)
	assertEquals(profileId, repository.contacts().single().profileId)
}
```

- [ ] **Step 2: Run focused tests and confirm failure**

Run: `./gradlew :app:test --tests com.netless.app.ConversationRepositoryTest`
Expected: FAIL because the repository and persistent index are missing.

- [ ] **Step 3: Implement repository projections**

Persist conversation and contact metadata alongside encrypted message content. Rebuild indexes from persisted records at construction so process restarts do not lose message visibility. Store delivery state separately from message body and expose it as a Flow.

- [ ] **Step 4: Run app tests**

Run: `./gradlew :app:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app domain/identity
git commit -m "add persistent conversations and contacts"
```

### Task 7: Implement Progressive-Disclosure Messenger UI

**Files:**
- Create: `app/src/main/kotlin/com/netless/app/MessengerViewModel.kt`
- Create: `app/src/main/kotlin/com/netless/app/MessengerScreens.kt`
- Modify: `app/src/main/kotlin/com/netless/app/NetlessApp.kt`
- Modify: `app/src/main/kotlin/com/netless/app/ProfileViewModel.kt`
- Test: `app/src/test/kotlin/com/netless/app/MessengerViewModelTest.kt`

**Interfaces:**
- `MessengerViewModel.uiState: StateFlow<MessengerUiState>`.
- `MessengerViewModel.selectConversation(id)`, `send(text)`, `setPolicy(policy)`, `confirmStrictMode()`, and `toggleExpertRoute()`.
- `MessengerUiState` contains current tab, conversations, selected conversation, draft, delivery states, network policy, strict warning state, and optional route details.
- Composables: `ChatListScreen`, `ConversationScreen`, `ContactsScreen`, `SettingsScreen`, `NetworkSettingsScreen`, and `RouteDetailsSheet`.

- [ ] **Step 1: Write ViewModel tests**

```kotlin
@Test
fun `strict mode requires confirmation`() = runTest {
	viewModel.setPolicy(TransportPolicy.Strict(TransportType.Bluetooth))
	assertTrue(viewModel.uiState.value.strictWarningVisible)
	assertFalse(viewModel.uiState.value.networkPolicy.isStrict)
	viewModel.confirmStrictMode()
	assertTrue(viewModel.uiState.value.networkPolicy.isStrict)
}

@Test
fun `delivery state is presented as simple label by default`() = runTest {
	viewModel.onDelivery(DeliveryState.Relaying)
	assertEquals("Relayed", viewModel.uiState.value.deliveryLabel)
}
```

- [ ] **Step 2: Run focused tests and confirm failure**

Run: `./gradlew :app:test --tests com.netless.app.MessengerViewModelTest`
Expected: FAIL because the new ViewModel is missing.

- [ ] **Step 3: Implement state and actions**

Keep message composition and network policy in the ViewModel. Convert protocol errors into actionable UI messages. Keep route details absent from default state until expert mode is enabled.

- [ ] **Step 4: Replace the current single-screen Compose layout**

Build Telegram-like chat list and conversation screens with Netless-specific colors, icons, and copy. Use `NavigationSuiteScaffold` if already available through existing dependencies; otherwise keep a small local tab state and Compose `AnimatedContent`. Use adaptive width checks for list-plus-chat split view. Put protocol controls under `Settings > Network`, not in the normal send flow.

- [ ] **Step 5: Add Compose semantics and UI tests**

Cover chat list navigation, send button enablement, strict warning confirmation, simple delivery labels, route detail expansion, and contact creation. Give all icon-only actions content descriptions.

- [ ] **Step 6: Run app tests**

Run: `./gradlew :app:test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app
git commit -m "rebuild app as messenger UI"
```

### Task 8: Add Adapter Capability Boundaries and CI Verification

**Files:**
- Create: `app/src/main/kotlin/com/netless/app/WifiAwareTransport.kt`
- Create: `app/src/main/kotlin/com/netless/app/LocalHotspotTransport.kt`
- Modify: `app/src/main/kotlin/com/netless/app/BleDiscovery.kt`
- Modify: `app/src/main/kotlin/com/netless/app/WifiDirectDiscovery.kt`
- Modify: `app/src/main/kotlin/com/netless/app/NetlessApplication.kt`
- Modify: `.github/workflows/ci.yml`
- Test: `app/src/test/kotlin/com/netless/app/TransportRegistryTest.kt`

**Interfaces:**
- Each unavailable adapter reports `TransportState.Unavailable` and never appears as a selectable working data route.
- `TransportRegistry.snapshot()` returns type, state, capabilities, and reason for unavailability.

- [ ] **Step 1: Write registry tests**

```kotlin
@Test
fun `unsupported transports remain unavailable`() {
	val snapshot = registry.snapshot().single { it.type == TransportType.WifiAware }
	assertEquals(TransportState.Unavailable, snapshot.state)
	assertFalse(snapshot.canConnect)
}
```

- [ ] **Step 2: Implement explicit unavailable adapters**

Do not create fake network success. Return a clear unavailable state and throw an actionable error from `connect`. Register Wi-Fi Direct only when the platform channel is initialized; register BLE data only after GATT transport exists.

- [ ] **Step 3: Add CI test coverage**

Update CI to run all module unit tests and the available Android lint/static checks. Do not add a local build requirement.

- [ ] **Step 4: Run the same CI commands through GitHub Actions**

Expected: all unit tests pass, no transport adapter advertises unsupported capabilities, and lint reports no new errors.

- [ ] **Step 5: Commit**

```bash
git add app .github/workflows/ci.yml
git commit -m "bound transport capabilities in ci"
```

## Plan Self-Review

- Control plane: Tasks 1 and 2 define adapters, capabilities, policy, route graph, route expiry, automatic selection, preferred selection, and strict selection.
- Data plane: Tasks 3, 4, and 5 define packet encoding, E2E payload boundaries, relay persistence, deduplication, acknowledgements, expiry, and mixed-hop forwarding.
- Persistence: Tasks 4 and 6 cover relay records, contacts, conversations, restart-safe indexes, delivery state, and 24-hour expiry.
- UX: Task 7 covers chats, contacts, settings, simple statuses, expert route details, strict warnings, responsive layouts, and accessibility semantics.
- Adapter limits: Task 8 keeps BLE discovery distinct from BLE data and prevents Wi-Fi Aware/Local Hotspot placeholders from appearing usable.
- No unresolved placeholders, undefined task dependencies, or locally required build steps remain.
