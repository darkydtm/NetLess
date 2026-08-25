package com.netless.app

import com.netless.common.NodeId
import com.netless.common.ProfileId
import com.netless.common.TrafficClass
import com.netless.network.Route
import com.netless.network.RouteHop
import com.netless.network.RouteMetrics
import com.netless.protocol.ContentEnvelope
import com.netless.protocol.DeliveryState
import com.netless.protocol.ControlCodec
import com.netless.protocol.Forward
import com.netless.protocol.Acknowledgement
import com.netless.protocol.HopAcknowledgement
import com.netless.protocol.DeliveryReceipt
import com.netless.protocol.Receipt
import com.netless.transport.TransportAdapter
import com.netless.transport.TransportConnection
import com.netless.transport.TransportEndpoint
import com.netless.transport.TransportPolicy
import com.netless.transport.TransportState
import com.netless.transport.TransportType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.netless.crypto.PublicKey
import com.netless.crypto.Signature
import com.netless.database.RelayStore
import java.util.Base64
import java.security.MessageDigest

class MeshRuntimeTest {
	@Test
	fun `delivers through three real runtimes with mixed authenticated hops`() = runTest {
		val network = ThreeNodeNetwork()
		val content = content()
		val result = network.origin.send(content, network.destinationId, TransportPolicy.Automatic())

		assertEquals(DeliveryState.Delivered, result.state)
		assertEquals(content, network.received)
		assertEquals(DeliveryReceipt(result.packetId, DeliveryState.Delivered, network.destinationId, 1_000L), network.destinationReceipt)
		assertEquals(network.destinationReceipt, network.relayReceipt)
		assertEquals(network.destinationReceipt, network.originReceipt)
		assertTrue(!network.destinationStore.hasPending(result.packetId))
		assertTrue(network.destinationStore.contains(result.packetId))
		assertEquals(listOf(TransportType.Bluetooth, TransportType.WifiDirect), network.usedTransports)
		assertTrue(!network.originStore.contains(result.packetId))
		assertTrue(!network.relayStore.contains(result.packetId))
		assertEquals(NodeId("relay"), network.forwarding.first().first)
		assertEquals(NodeId("destination"), network.forwarding.first().second)
		assertEquals(NodeId("destination"), network.forwarding.last().first)
		assertEquals(null, network.forwarding.last().second)
	}

	@Test
	fun `duplicate packet replays terminal receipt without handing off content twice`() = runTest {
		val network = ThreeNodeNetwork()
		val result = network.origin.send(content(), network.destinationId, TransportPolicy.Automatic())
		val packet = network.destinationPacket!!

		assertEquals(network.destinationReceipt, network.destination.receive(packet, TransportType.WifiDirect))
		assertEquals(1, network.contentDeliveries)
		assertEquals(result.packetId, network.destinationReceipt?.packetId)
		assertEquals(network.destinationId, network.destinationReceipt?.nodeId)
		assertTrue(!network.destinationStore.hasPending(result.packetId))
		assertTrue(network.destinationStore.contains(result.packetId))
	}

	@Test
	fun `retains failed three-node delivery and rejects forged and duplicate receipts`() = runTest {
		val network = ThreeNodeNetwork(failDestination = true)
		val result = network.origin.send(content(), network.destinationId, TransportPolicy.Automatic())

		assertEquals(DeliveryState.Failed, result.state)
		assertTrue(network.originStore.contains(result.packetId))
		assertTrue(network.relayStore.contains(result.packetId))
		assertEquals(DeliveryReceipt(result.packetId, DeliveryState.Failed, network.destinationId, 1_000L), network.relayFailureReceipt)
		val forged = ControlCodec.receipt(DeliveryReceipt(result.packetId, DeliveryState.Delivered, NodeId("attacker"), 1_000L))
		assertTrue(runCatching { network.relay.receiveFrame(forged, TransportType.WifiDirect) }.isFailure)
		assertTrue(network.relayStore.contains(result.packetId))
		assertTrue(runCatching { network.relay.receiveFrame(ControlCodec.receipt(DeliveryReceipt(result.packetId, DeliveryState.Delivered, network.destinationId, 1_000L)), TransportType.Bluetooth) }.isFailure)
	}
	@Test
	fun `forwards one packet through bluetooth then wifi direct`() = runTest {
		val network = FakeNetwork()
		val runtime = network.runtime()
		val result = runtime.send(content(), NodeId("destination"), TransportPolicy.Automatic())

		assertEquals(DeliveryState.Delivered, result.state)
		assertEquals(listOf(TransportType.Bluetooth), network.usedTransports)
		assertEquals(0, network.relayStore.count())
	}

	@Test
	fun `preferred transport falls back when unavailable`() = runTest {
		val network = FakeNetwork().also { it.disabled += TransportType.Bluetooth }
		val runtime = network.runtime()

		assertEquals(DeliveryState.Delivered, runtime.send(content(), NodeId("destination"), TransportPolicy.Preferred(listOf(TransportType.Bluetooth, TransportType.WifiDirect))).state)
		assertEquals(listOf(TransportType.WifiDirect), network.usedTransports)
	}

	@Test
	fun `missing packet signature emits failure observation`() = runTest {
		val network = FakeNetwork()
		val runtime = network.runtime(verifySignature = { _, _ -> false })
		val result = runCatching { runtime.receive(network.packet(), TransportType.Bluetooth) }

		assertTrue(result.isFailure)
		assertEquals(DeliveryState.Failed, runtime.observeDelivery(network.packetId).first().state)
	}

	@Test
	fun `forged and duplicate receipts are rejected`() = runTest {
		val network = FakeNetwork()
		val runtime = network.runtime()
		network.relayStore.put(network.packet(), network.packetId, Long.MAX_VALUE, NodeId("relay"))

		assertTrue(runCatching { runtime.receiveFrame(ControlCodec.receipt(DeliveryReceipt(network.packetId, DeliveryState.Delivered, NodeId("attacker"), 0L)), TransportType.Bluetooth) }.isFailure)
		assertTrue(network.relayStore.contains(network.packetId))

		runtime.receiveFrame(ControlCodec.receipt(DeliveryReceipt(network.packetId, DeliveryState.Delivered, NodeId("destination"), 0L)), TransportType.Bluetooth)
		assertTrue(!network.relayStore.contains(network.packetId))
		assertTrue(runCatching { runtime.receiveFrame(ControlCodec.receipt(DeliveryReceipt(network.packetId, DeliveryState.Delivered, NodeId("destination"), 0L)), TransportType.Bluetooth) }.isFailure)
	}

	@Test
	fun `receipt is not admitted for a final-node record`() = runTest {
		val network = FakeNetwork()
		val runtime = network.runtime()
		network.relayStore.put(network.packet(), network.packetId, Long.MAX_VALUE, null)

		assertTrue(runCatching {
			runtime.receiveFrame(ControlCodec.receipt(DeliveryReceipt(network.packetId, DeliveryState.Delivered, NodeId("destination"), 0L)), TransportType.Bluetooth)
		}.isFailure)
		assertTrue(network.relayStore.contains(network.packetId))
	}

	@Test
	fun `relay rewrite preserves integrity for destination validation`() = runTest {
		val network = FakeNetwork()
		val packet = com.netless.protocol.PacketEnvelope(
			com.netless.protocol.ForwardingEnvelope(network.packetId, NodeId("destination"), NodeId("relay"), NodeId("relay"), 0, 2, TrafficClass.Reliable, ByteArray(32), NodeId("local")),
			content().copy(senderSignature = byteArrayOf(9)), createdAtEpochMillis = 100, expiresAtEpochMillis = Long.MAX_VALUE,
		)
		val now = 1000L
		val input = packet.copy(forwarding = packet.forwarding.copy(perHopIntegrity = byteArrayOf(0)), content = packet.content.copy(senderSignature = byteArrayOf(0)))
		val bytes = com.netless.protocol.VersionedPacketCodec.encode(packet.copy(forwarding = packet.forwarding.copy(perHopIntegrity = MessageDigest.getInstance("SHA-256").digest(com.netless.protocol.VersionedPacketCodec.encode(input, now)))))

		network.runtime(NodeId("relay"), now).receive(bytes, TransportType.Bluetooth)
		val destination = network.runtime(NodeId("destination"), now, relayStore = null)
		assertEquals(DeliveryState.Delivered, destination.receive(network.forwardedPacket!!, TransportType.WifiDirect).state)
	}

	private fun content() = ContentEnvelope("event", ProfileId("sender"), listOf(ProfileId("destination")), byteArrayOf(1), byteArrayOf(2))
}

private class ThreeNodeNetwork(failDestination: Boolean = false) {
	val destinationId = NodeId("destination")
	val usedTransports = mutableListOf<TransportType>()
	val originStore = RelayStore()
	val relayStore = RelayStore()
	val destinationStore = RelayStore()
	var received: ContentEnvelope? = null
	var contentDeliveries = 0
	var destinationPacket: ByteArray? = null
	val forwarding = mutableListOf<Pair<NodeId, NodeId?>>()
	val relayId = NodeId("relay")
	var destinationReceipt: DeliveryReceipt? = null
	var relayReceipt: DeliveryReceipt? = null
	var originReceipt: DeliveryReceipt? = null
	var relayFailureReceipt: DeliveryReceipt? = null
	private val keys = mapOf("origin" to PublicKey(byteArrayOf(1)), "relay" to PublicKey(byteArrayOf(2)), "destination" to PublicKey(byteArrayOf(3)))
	lateinit var origin: MeshRuntime
	lateinit var relay: MeshRuntime
	lateinit var destination: MeshRuntime

	init {
		val nodes = mapOf(NodeId("origin") to { origin }, NodeId("relay") to { relay }, destinationId to { destination })
		fun runtime(node: NodeId, store: RelayStore?, transports: List<Pair<TransportType, NodeId>>, onContent: suspend (ContentEnvelope) -> Unit = {}) = MeshRuntime(
			node, TransportRegistry().also { registry -> transports.forEach { (type, peer) -> registry.register(NodeAdapter(type, node, peer, nodes, this, failDestination)) } },
			{ _, _ -> Route(listOf(node, transports.first().second), RouteMetrics(1.0, 1.0, 1.0, 1.0), hops = listOf(RouteHop(node, transports.first().second, transports.first().first, endpoint(transports.first().first, transports.first().second), RouteMetrics(1.0, 1.0, 1.0, 1.0), Long.MAX_VALUE))) },
			relayStore = store, signPacket = { sign(keys.getValue(node.value), it) }, verifySenderSignature = { packet, data ->
				keys[packet.content.sender.value]?.let { sign(it, data).contentEquals(packet.content.senderSignature) } == true
			}, onContent = onContent,
			localIdentity = keys.getValue(node.value), signSession = { signSession(keys.getValue(node.value), it) }, verifySession = { key, data, signature -> signSession(key, data) == signature }, nowMillis = { 1_000L }
		)
		origin = runtime(NodeId("origin"), originStore, listOf(TransportType.Bluetooth to NodeId("relay")))
		relay = runtime(NodeId("relay"), relayStore, listOf(TransportType.WifiDirect to destinationId))
		destination = runtime(destinationId, destinationStore, emptyList()) { if (failDestination) error("destination rejected content") else { received = it; contentDeliveries++ } }
	}

	private fun sign(key: PublicKey, data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(key.encoded + data)
	private fun signSession(key: PublicKey, data: ByteArray) = Signature(sign(key, data))

	private fun endpoint(type: TransportType, node: NodeId) = TransportEndpoint(node, type.name, mapOf("nodeId" to node.value, "identityKey" to Base64.getEncoder().encodeToString(keys.getValue(node.value).encoded)))

	private class NodeAdapter(override val type: TransportType, private val local: NodeId, private val peer: NodeId, private val nodes: Map<NodeId, () -> MeshRuntime>, private val network: ThreeNodeNetwork, private val fail: Boolean) : TransportAdapter {
		override val availability = MutableStateFlow(TransportState.Idle)
		override suspend fun connectAuthenticated(endpoint: TransportEndpoint, request: com.netless.transport.AuthenticatedConnectionRequest): TransportConnection {
			require(endpoint.nodeId == peer && request.expectedPeerIdentity == network.keys.getValue(peer.value))
			network.usedTransports += type
			return object : TransportConnection {
				override val peerIdentity = network.keys.getValue(peer.value)
				private var incoming = kotlinx.coroutines.flow.emptyFlow<ByteArray>()
				override val incomingPackets get() = incoming
					override suspend fun send(packet: ByteArray) {
						val forwarded = (ControlCodec.decode(packet) as Forward).packet
						val envelope = com.netless.protocol.VersionedPacketCodec.decode(forwarded)
						network.forwarding += envelope.forwarding.currentNodeId to envelope.forwarding.nextHop
						if (peer.value == "destination") network.destinationPacket = forwarded
						val response = nodes.getValue(peer)().receiveFrame(packet, type)
					when (val decoded = ControlCodec.decode(response)) {
						is Receipt -> when (peer.value) { "destination" -> network.destinationReceipt = decoded.value; "relay" -> { network.relayReceipt = decoded.value; network.originReceipt = decoded.value } }
						is Acknowledgement -> if (!decoded.value.accepted) network.relayFailureReceipt = DeliveryReceipt(decoded.value.packetId, DeliveryState.Failed, peer, 1_000L)
					}
					incoming = kotlinx.coroutines.flow.flowOf(response)
				}
				override suspend fun close() = Unit
			}
		}
		override suspend fun connect(endpoint: TransportEndpoint): TransportConnection = error("use authenticated connect")
		override fun supports(capability: com.netless.transport.DiscoveryCapability) = true
	}
}

private class FakeNetwork {
	val usedTransports = mutableListOf<TransportType>()
	val disabled = mutableSetOf<TransportType>()
	val relayStore = RelayStore()
	private val identityKey = PublicKey(byteArrayOf(1, 2, 3))

	var forwardedPacket: ByteArray? = null

	fun runtime(local: NodeId = NodeId("local"), now: Long = 1000L, verifySignature: suspend (com.netless.protocol.PacketEnvelope, ByteArray) -> Boolean = { _, _ -> true }, relayStore: RelayStore? = this.relayStore) = MeshRuntime(
		local,
		TransportRegistry().also { registry ->
			registry.register(FakeAdapter(TransportType.Bluetooth, this))
			registry.register(FakeAdapter(TransportType.WifiDirect, this))
		},
		{ destination, policy ->
			val next = if (local == NodeId("relay")) destination else NodeId("relay")
			val transport = if (local == NodeId("relay")) TransportType.WifiDirect else TransportType.Bluetooth
			val hops = listOf(
				RouteHop(local, next, transport, endpoint(transport, next.value), metrics(), Long.MAX_VALUE),
			)
			Route(listOf(local, next), metrics(), hops = hops)
		},
		relayStore = relayStore,
		signPacket = { byteArrayOf(9) },
		verifySenderSignature = verifySignature,
		localIdentity = identityKey,
		signSession = { Signature(byteArrayOf(9)) },
		verifySession = { _, _, _ -> true },
		nowMillis = { now },
	)

	val packetId = com.netless.common.PacketId("packet")
	fun packet() = com.netless.protocol.VersionedPacketCodec.encode(com.netless.protocol.PacketEnvelope(
		com.netless.protocol.ForwardingEnvelope(packetId, NodeId("destination"), NodeId("local"), 0, 1, TrafficClass.Reliable, ByteArray(32), NodeId("local")),
		content().copy(senderSignature = byteArrayOf(1)), createdAtEpochMillis = 0, expiresAtEpochMillis = Long.MAX_VALUE
	))

	private fun endpoint(type: TransportType, node: String) = TransportEndpoint(NodeId(node), type.name, mapOf(
		"nodeId" to node,
		"identityKey" to Base64.getEncoder().encodeToString(identityKey.encoded),
	))
	private fun metrics() = RouteMetrics(1.0, 1.0, 1.0, 1.0)
}

private class FakeAdapter(override val type: TransportType, private val network: FakeNetwork) : TransportAdapter {
	override val availability = MutableStateFlow(TransportState.Idle)
	override suspend fun connect(endpoint: TransportEndpoint): TransportConnection {
		if (type in network.disabled) error("unavailable")
		network.usedTransports += type
		return object : TransportConnection {
			override val peerIdentity: PublicKey = network.identityKey
			override val incomingPackets: kotlinx.coroutines.flow.Flow<ByteArray>
				get() = incoming
				override suspend fun send(packet: ByteArray) {
				network.forwardedPacket = (ControlCodec.decode(packet) as Forward).packet
				val forwarded = ControlCodec.decode(packet) as Forward
				val decoded = com.netless.protocol.VersionedPacketCodec.decode(forwarded.packet)
				incoming = kotlinx.coroutines.flow.flowOf(
					ControlCodec.acknowledgement(HopAcknowledgement(decoded.forwarding.packetId, endpoint.nodeId, true)),
					ControlCodec.receipt(DeliveryReceipt(decoded.forwarding.packetId, DeliveryState.Delivered, decoded.forwarding.finalNodeId, 0L))
				)
			}
			 override suspend fun close() = Unit
			private var incoming: kotlinx.coroutines.flow.Flow<ByteArray> = emptyFlow()
		 }
	}
	override fun supports(capability: com.netless.transport.DiscoveryCapability) = true
}
