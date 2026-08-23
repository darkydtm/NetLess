package com.netless.transport

import com.netless.common.NodeId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
	fun transportsExposePlatformIndependentContracts() = runBlocking {
		val endpoint = TransportEndpoint(NodeId("node-a"), "address")
		val discoveredNode = DiscoveredNode(
			nodeId = endpoint.nodeId,
			endpoint = endpoint,
			capabilities = TransportCapabilities(
				canAdvertise = true,
				canAcceptIncoming = true,
				maxConcurrentConnections = 1,
				supportsRelay = true,
				supportsLowLatency = true,
			),
		)
		val advertisement = DiscoveryAdvertisement(
			discoveryHash = "hash",
			protocolVersion = 1,
			sessionId = "session",
			capabilities = setOf(DiscoveryCapability.Advertise),
			transportHints = setOf(TransportType.Bluetooth),
		)
		var discoveryStarted = false
		var discoveryStopped = false
		var advertisedAdvertisement: DiscoveryAdvertisement? = null
		val discovery: DiscoveryTransport = object : DiscoveryTransport {
			override suspend fun startDiscovery(): Flow<DiscoveredNode> {
				discoveryStarted = true
				return flowOf(discoveredNode)
			}
			override suspend fun stopDiscovery() {
				discoveryStopped = true
			}
			override suspend fun advertise(advertisement: DiscoveryAdvertisement) {
				advertisedAdvertisement = advertisement
			}
		}

		val incomingPacket = byteArrayOf(1, 2, 3)
		val outgoingPacket = byteArrayOf(4, 5, 6)
		var connectedEndpoint: TransportEndpoint? = null
		var sentPacket: ByteArray? = null
		var connectionClosed = false
		val data: DataTransport = object : DataTransport {
			override val type = TransportType.Bluetooth
			override val state: Flow<TransportState> = flowOf(
				TransportState.Connecting,
				TransportState.Connected,
				TransportState.Closed,
			)
			override suspend fun connect(endpoint: TransportEndpoint): TransportConnection {
				connectedEndpoint = endpoint
				return object : TransportConnection {
					override val incomingPackets: Flow<ByteArray> = flowOf(incomingPacket)
					override suspend fun send(packet: ByteArray) {
						sentPacket = packet.copyOf()
					}
					override suspend fun close() {
						connectionClosed = true
					}
				}
			}
		}

		val discoveredNodes = mutableListOf<DiscoveredNode>()
		discovery.startDiscovery().collect { discoveredNodes += it }
		discovery.advertise(advertisement)
		discovery.stopDiscovery()

		val observedStates = mutableListOf<TransportState>()
		data.state.collect { observedStates += it }
		val connection = data.connect(endpoint)
		val receivedPackets = mutableListOf<ByteArray>()
		connection.incomingPackets.collect { receivedPackets += it }
		connection.send(outgoingPacket)
		connection.close()

		assertTrue(discoveryStarted)
		assertTrue(discoveryStopped)
		assertEquals(listOf(discoveredNode), discoveredNodes)
		assertEquals(advertisement, advertisedAdvertisement)
		assertEquals(TransportType.Bluetooth, data.type)
		assertEquals(listOf(TransportState.Connecting, TransportState.Connected, TransportState.Closed), observedStates)
		assertEquals(endpoint, connectedEndpoint)
		assertContentEquals(incomingPacket, receivedPackets.single())
		assertContentEquals(outgoingPacket, sentPacket ?: error("send was not called"))
		assertTrue(connectionClosed)
	}
}
