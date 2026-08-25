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

class MeshRuntimeTest {
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

	private fun content() = ContentEnvelope("event", ProfileId("sender"), listOf(ProfileId("destination")), byteArrayOf(1), byteArrayOf(2))
}

private class FakeNetwork {
	val usedTransports = mutableListOf<TransportType>()
	val disabled = mutableSetOf<TransportType>()
	val relayStore = RelayStore()
	private val identityKey = PublicKey(byteArrayOf(1, 2, 3))

	fun runtime(verifySignature: suspend (com.netless.protocol.PacketEnvelope, ByteArray) -> Boolean = { _, _ -> true }) = MeshRuntime(
		NodeId("local"),
		TransportRegistry().also { registry ->
			registry.register(FakeAdapter(TransportType.Bluetooth, this))
			registry.register(FakeAdapter(TransportType.WifiDirect, this))
		},
		{ destination, policy ->
			val hops = listOf(
				RouteHop(NodeId("local"), NodeId("relay"), TransportType.Bluetooth, endpoint(TransportType.Bluetooth, "relay"), metrics(), Long.MAX_VALUE),
				RouteHop(NodeId("relay"), destination, TransportType.WifiDirect, endpoint(TransportType.WifiDirect, "destination"), metrics(), Long.MAX_VALUE),
			)
			Route(listOf(NodeId("local"), NodeId("relay"), destination), metrics(), hops = hops)
		},
		relayStore = relayStore,
		signPacket = { byteArrayOf(9) },
		verifySenderSignature = verifySignature,
		localIdentity = identityKey,
		signSession = { Signature(byteArrayOf(9)) },
		verifySession = { _, _, _ -> true },
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
				val forwarded = ControlCodec.decode(packet) as Forward
				val decoded = com.netless.protocol.VersionedPacketCodec.decode(forwarded.packet)
				incoming = kotlinx.coroutines.flow.flowOf(
					ControlCodec.acknowledgement(HopAcknowledgement(decoded.forwarding.packetId, endpoint.nodeId, true)),
					ControlCodec.receipt(DeliveryReceipt(decoded.forwarding.packetId, DeliveryState.Delivered, endpoint.nodeId, 0L))
				)
			}
			 override suspend fun close() = Unit
			private var incoming: kotlinx.coroutines.flow.Flow<ByteArray> = emptyFlow()
		 }
	}
	override fun supports(capability: com.netless.transport.DiscoveryCapability) = true
}
