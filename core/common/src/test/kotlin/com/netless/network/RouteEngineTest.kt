package com.netless.network

import com.netless.common.NodeId
import com.netless.transport.TransportEndpoint
import com.netless.transport.TransportPolicy
import com.netless.transport.TransportType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RouteEngineTest {
	private val engine = RouteEngine()
	private val target = NodeId("target")

	@Test
	fun `selects a route containing different transports per hop`() {
		val route = engine.select(
			target,
			graphOf(
				hop("a", "b", TransportType.Bluetooth),
				hop("b", "target", TransportType.WifiDirect),
			),
			TransportPolicy.Automatic(),
			100L,
		)

		assertEquals(listOf(TransportType.Bluetooth, TransportType.WifiDirect), route!!.hops.map { it.transport })
	}

	@Test
	fun `strict policy rejects mixed route`() {
		assertNull(
			engine.select(
				target,
				graphOf(
					hop("a", "b", TransportType.Bluetooth),
					hop("b", "target", TransportType.WifiDirect),
				),
				TransportPolicy.Strict(TransportType.WifiDirect),
				100L,
			),
		)
	}

	@Test
	fun `expired hop is excluded`() {
		assertNull(
			engine.select(
				target,
				graphOf(hop("a", "target", TransportType.WifiDirect, expiresAt = 99L)),
				TransportPolicy.Automatic(),
				100L,
			),
		)
	}

	@Test
	fun `preferred policy ranks routes by first matching transport`() {
		val route = engine.select(
			target,
			graphOf(
				hop("a", "target", TransportType.Bluetooth),
				hop("b", "target", TransportType.WifiDirect),
			),
			TransportPolicy.Preferred(listOf(TransportType.WifiDirect, TransportType.Bluetooth)),
			100L,
		)

		assertEquals(TransportType.WifiDirect, route!!.hops.single().transport)
	}

	private fun graphOf(vararg hops: RouteHop): RouteGraph = RouteGraph(hops.toList())

	private fun hop(
		node: String,
		nextNode: String,
		transport: TransportType,
		expiresAt: Long = Long.MAX_VALUE,
	): RouteHop = RouteHop(
		NodeId(node),
		NodeId(nextNode),
		transport,
		TransportEndpoint(NodeId(nextNode), nextNode),
		RouteMetrics(1.0, 1.0, 1.0, 1.0),
		expiresAt,
	)
}
