package com.netless.network

import com.netless.common.NodeId
import com.netless.transport.TransportEndpoint
import com.netless.transport.TransportPolicy
import com.netless.transport.TransportType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
	fun `route expiry is the earliest hop expiry`() {
		val graph = graphOf(
			hop("a", "b", TransportType.Bluetooth, expiresAt = 150L),
			hop("b", "target", TransportType.WifiDirect, expiresAt = 250L),
		)

		assertEquals(150L, graph.routesTo(target, 100L).single().expiresAtMillis)
		assertNull(engine.select(target, graph, TransportPolicy.Automatic(), 150L))
	}

	@Test
	fun `independent component does not limit route expiry`() {
		val graph = graphOf(
			hop("a", "target", TransportType.WifiDirect, expiresAt = 250L),
			hop("x", "y", TransportType.Bluetooth, expiresAt = 150L),
		)

		assertEquals(250L, graph.routesTo(target, 100L).single().expiresAtMillis)
	}

	@Test
	fun `route preserves hop endpoints and metrics`() {
		val first = hop("a", "b", TransportType.Bluetooth, expiresAt = 150L).copy(
			endpoint = TransportEndpoint(NodeId("b"), "bluetooth-b"),
			metrics = RouteMetrics(10.0, 2.0, 3.0, 0.9),
		)
		val second = hop("b", "target", TransportType.WifiDirect, expiresAt = 250L).copy(
			endpoint = TransportEndpoint(target, "wifi-target"),
			metrics = RouteMetrics(20.0, 4.0, 5.0, 0.8),
		)

		val route = graphOf(first, second).routesTo(target, 100L).single()

		assertEquals(listOf(first, second), route.hops)
		assertEquals(RouteMetrics(10.0, 6.0, 8.0, 0.72), route.metrics)
	}

	@Test
	fun `cyclic paths respect hop limit`() {
		val graph = RouteGraph(
			listOf(
				hop("a", "b", TransportType.Bluetooth),
				hop("b", "a", TransportType.Bluetooth),
				hop("b", "target", TransportType.WifiDirect),
			),
			maxHops = 1,
		)

		assertNull(engine.select(target, graph, TransportPolicy.Automatic(), 100L))
	}

	@Test
	fun `equal routes are selected in node order`() {
		val route = engine.select(
			target,
			graphOf(
				hop("a", "c", TransportType.Bluetooth),
				hop("c", "target", TransportType.WifiDirect),
				hop("a", "b", TransportType.Bluetooth),
				hop("b", "target", TransportType.WifiDirect),
			),
			TransportPolicy.Automatic(),
			100L,
		)

		assertEquals(listOf(NodeId("a"), NodeId("b"), target), route!!.nodes)
	}

	@Test
	fun `preferred policy uses the first hop`() {
		val route = engine.select(
			target,
			graphOf(
				hop("a", "b", TransportType.Bluetooth),
				hop("b", "target", TransportType.WifiDirect),
				hop("a", "c", TransportType.WifiDirect),
				hop("c", "target", TransportType.Bluetooth),
			),
			TransportPolicy.Preferred(listOf(TransportType.WifiDirect, TransportType.Bluetooth)),
			100L,
		)

		assertEquals(TransportType.WifiDirect, route!!.hops.first().transport)
		assertEquals(NodeId("c"), route.nodes[1])
	}

	@Test
	fun `preferred policy falls back from unavailable transport`() {
		val route = engine.select(
			target,
			graphOf(
				hop("a", "b", TransportType.WifiDirect),
				hop("b", "target", TransportType.WifiDirect).copy(metrics = RouteMetrics(1.0, 1.0, 1.0, 0.0)),
				hop("a", "c", TransportType.Bluetooth),
				hop("c", "target", TransportType.Bluetooth),
			),
			TransportPolicy.Preferred(listOf(TransportType.WifiDirect, TransportType.Bluetooth)),
			100L,
		)

		assertEquals(TransportType.Bluetooth, route!!.hops.first().transport)
	}

	@Test
	fun `valid route survives expired incoming hop`() {
		val routes = graphOf(
			hop("root", "a", TransportType.Bluetooth, expiresAt = 99L),
			hop("a", "target", TransportType.WifiDirect),
		).routesTo(target, 100L)

		assertEquals(listOf(NodeId("a"), target), routes.single().nodes)
	}

	@Test
	fun `traverses cyclic component without repeating nodes in a path`() {
		val routes = graphOf(
			hop("a", "b", TransportType.Bluetooth),
			hop("b", "a", TransportType.Bluetooth),
			hop("b", "target", TransportType.WifiDirect),
		).routesTo(target, 100L)

		assertTrue(routes.any { it.nodes == listOf(NodeId("a"), NodeId("b"), target) })
		assertTrue(routes.all { it.nodes.distinct().size == it.nodes.size })
	}

	@Test
	fun `engine passes caller time to selector`() {
		val selector = RouteSelector { 1_000L }
		val engine = RouteEngine(selector)

		assertEquals(
			TransportType.WifiDirect,
			engine.select(
				target,
				graphOf(hop("a", "target", TransportType.WifiDirect, expiresAt = 100L)),
				TransportPolicy.Automatic(),
				0L,
			)!!.hops.single().transport,
		)
	}

	@Test
	fun `graph and selector use the same hop limit`() {
		val graph = RouteGraph(
			listOf(
				hop("a", "b", TransportType.Bluetooth),
				hop("b", "target", TransportType.WifiDirect),
			),
			maxHops = 1,
		)

		assertNull(engine.select(target, graph, TransportPolicy.Automatic(), 100L))
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
