package com.netless.transport

import com.netless.common.NodeId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertFailsWith

class TransportApiTest {
	@Test
	fun capabilitiesRejectNegativeConnectionCount() {
		assertFailsWith<IllegalArgumentException> {
			TransportCapabilities(
				canAdvertise = true,
				canAcceptIncoming = true,
				maxConcurrentConnections = -1,
				supportsRelay = true,
				supportsLowLatency = true,
			)
		}
	}

	@Test
	fun transportsExposePlatformIndependentContracts() {
		val discovery: DiscoveryTransport = object : DiscoveryTransport {
			override suspend fun startDiscovery(): Flow<DiscoveredNode> = emptyFlow()
			override suspend fun stopDiscovery() = Unit
			override suspend fun advertise(advertisement: DiscoveryAdvertisement) = Unit
		}
		val data: DataTransport = object : DataTransport {
			override val type = TransportType.Bluetooth
			override val state: Flow<TransportState> = emptyFlow()
			override suspend fun connect(endpoint: TransportEndpoint): TransportConnection =
				object : TransportConnection {
					override val incomingPackets: Flow<ByteArray> = emptyFlow()
					override suspend fun send(packet: ByteArray) = Unit
					override suspend fun close() = Unit
				}
		}

		assert(discovery is DiscoveryTransport)
		assert(data.type == TransportType.Bluetooth)
		assert(TransportEndpoint(NodeId("node-a"), "address").nodeId == NodeId("node-a"))
	}
}
