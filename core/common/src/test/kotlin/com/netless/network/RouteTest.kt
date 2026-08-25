package com.netless.network

import com.netless.common.NodeId
import com.netless.transport.TransportEndpoint
import com.netless.transport.TransportType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RouteTest {
	@Test
	fun rejectsEmptyAndCyclicRoutes() {
		val metrics = RouteMetrics(1.0, 1.0, 1.0, 1.0)

		assertFailsWith<IllegalArgumentException> { Route(emptyList(), metrics) }
		assertFailsWith<IllegalArgumentException> {
			Route(listOf(NodeId("a"), NodeId("b"), NodeId("a")), metrics)
		}
	}

	@Test
	fun callerMutationCannotChangeRoute() {
		val metrics = RouteMetrics(1.0, 1.0, 1.0, 1.0)
		val callerNodes = mutableListOf(NodeId("a"), NodeId("b"))
		val route = Route(callerNodes, metrics)

		callerNodes[0] = NodeId("changed")
		callerNodes += NodeId("c")

		assertEquals(listOf(NodeId("a"), NodeId("b")), route.nodes)
	}

	@Test
	fun copyPreservesValueSemanticsAndSnapshotsNodes() {
		val metrics = RouteMetrics(1.0, 1.0, 1.0, 1.0)
		val copiedNodes = mutableListOf(NodeId("c"))
		val route = Route(listOf(NodeId("a"), NodeId("b")), metrics)
		val copy = route.copy(nodes = copiedNodes)

		copiedNodes[0] = NodeId("changed")

		assertEquals(route, route.copy())
		assertEquals(listOf(NodeId("c")), copy.nodes)
		assertEquals(metrics, copy.metrics)
	}

	@Test
	fun expiryParticipatesInValueSemanticsAndCopy() {
		val metrics = RouteMetrics(1.0, 1.0, 1.0, 1.0)
		val route = Route(listOf(NodeId("a"), NodeId("b")), metrics, expiresAtMillis = 100L)
		val copy = route.copy(expiresAtMillis = 200L)

		assertEquals(Long.MAX_VALUE, Route(listOf(NodeId("a")), metrics).expiresAtMillis)
		assertEquals(route, route.copy())
		assertEquals(200L, copy.expiresAtMillis)
		assertTrue(route.toString().contains("expiresAtMillis=100"))
		assertFailsWith<IllegalArgumentException> {
			Route(listOf(NodeId("a")), metrics, expiresAtMillis = -1L)
		}
		assertNotEquals(route, copy)
	}

	@Test
	fun supportsDestructuring() {
		val metrics = RouteMetrics(1.0, 1.0, 1.0, 1.0)
		val route = Route(listOf(NodeId("a"), NodeId("b")), metrics)
		val (nodes, destructuredMetrics) = route

		assertEquals(route.nodes, nodes)
		assertEquals(route.metrics, destructuredMetrics)
	}

	@Test
	fun `validates hop edges and copy nodes`() {
		val metrics = RouteMetrics(1.0, 1.0, 1.0, 1.0)
		val hop = RouteHop(NodeId("a"), NodeId("b"), TransportType.Bluetooth, TransportEndpoint(NodeId("b"), "b"), metrics, 100L)

		assertFailsWith<IllegalArgumentException> {
			Route(listOf(NodeId("a"), NodeId("c")), metrics, hops = listOf(hop))
		}
		val route = Route(listOf(NodeId("a"), NodeId("b")), metrics, hops = listOf(hop))
		assertFailsWith<IllegalArgumentException> { route.copy(nodes = listOf(NodeId("a"), NodeId("c"))) }
	}

}
