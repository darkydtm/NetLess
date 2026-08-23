package com.netless.network

import com.netless.common.NodeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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

}
