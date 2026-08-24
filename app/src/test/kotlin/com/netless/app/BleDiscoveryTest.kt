package com.netless.app

import com.netless.common.NodeId
import com.netless.transport.DiscoveredNode
import com.netless.transport.DiscoveryAdvertisement
import com.netless.transport.DiscoveryCapability
import com.netless.transport.TransportCapabilities
import com.netless.transport.TransportEndpoint
import com.netless.transport.TransportType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class BleDiscoveryTest {
	@Test
	fun advertisementRoundTripPreservesDiscoveryData() {
		val source = DiscoveryAdvertisement(
			discoveryHash = "node-hash",
			protocolVersion = 1,
			sessionId = "session",
			capabilities = setOf(DiscoveryCapability.Relay, DiscoveryCapability.Advertise),
			transportHints = setOf(TransportType.Bluetooth),
		)

		assertEquals(source, BleAdvertisementCodec.decode(BleAdvertisementCodec.encode(source)))
	}

	@Test
	fun contactStoreUpsertsDiscoveredNodes() = runTest {
		val store = ContactStore()
		val node = DiscoveredNode(
			NodeId("node"),
			TransportEndpoint(NodeId("node"), "AA:BB"),
			TransportCapabilities(true, true, 1, true, false),
		)

		store.upsert(node)
		store.upsert(node.copy(endpoint = TransportEndpoint(NodeId("node"), "CC:DD")))

		assertEquals("CC:DD", store.contacts.first().single().endpoint.address)
	}
}
