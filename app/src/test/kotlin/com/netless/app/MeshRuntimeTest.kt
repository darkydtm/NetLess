package com.netless.app

import com.netless.common.NodeId
import com.netless.common.ProfileId
import com.netless.common.TrafficClass
import com.netless.network.Route
import com.netless.network.RouteHop
import com.netless.network.RouteMetrics
import com.netless.protocol.ContentEnvelope
import com.netless.protocol.DeliveryState
import com.netless.transport.TransportAdapter
import com.netless.transport.TransportConnection
import com.netless.transport.TransportEndpoint
import com.netless.transport.TransportPolicy
import com.netless.transport.TransportState
import com.netless.transport.TransportType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class MeshRuntimeTest {
	@Test
	fun `forwards one packet through bluetooth then wifi direct`() = runTest {
		val network = FakeNetwork()
		val runtime = network.runtime()
		val result = runtime.send(content(), NodeId("destination"), TransportPolicy.Automatic())

		assertEquals(DeliveryState.Delivered, result.state)
		assertEquals(listOf(TransportType.Bluetooth, TransportType.WifiDirect), network.usedTransports)
	}

	@Test
	fun `preferred transport falls back when unavailable`() = runTest {
		val network = FakeNetwork().also { it.disabled += TransportType.Bluetooth }
		val runtime = network.runtime()

		assertEquals(DeliveryState.Delivered, runtime.send(content(), NodeId("destination"), TransportPolicy.Preferred(listOf(TransportType.Bluetooth, TransportType.WifiDirect))).state)
		assertEquals(listOf(TransportType.WifiDirect), network.usedTransports)
	}

	private fun content() = ContentEnvelope("event", ProfileId("sender"), listOf(ProfileId("destination")), byteArrayOf(1), byteArrayOf(2))
}

private class FakeNetwork {
	val usedTransports = mutableListOf<TransportType>()
	val disabled = mutableSetOf<TransportType>()

	fun runtime() = MeshRuntime(
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
	)

	private fun endpoint(type: TransportType, node: String) = TransportEndpoint(NodeId(node), type.name)
	private fun metrics() = RouteMetrics(1.0, 1.0, 1.0, 1.0)
}

private class FakeAdapter(override val type: TransportType, private val network: FakeNetwork) : TransportAdapter {
	override val availability = MutableStateFlow(TransportState.Idle)
	override suspend fun connect(endpoint: TransportEndpoint): TransportConnection {
		if (type in network.disabled) error("unavailable")
		network.usedTransports += type
		return object : TransportConnection {
			override val incomingPackets = emptyFlow<ByteArray>()
			override suspend fun send(packet: ByteArray) = Unit
			override suspend fun close() = Unit
		}
	}
	override fun supports(capability: com.netless.transport.DiscoveryCapability) = true
}
