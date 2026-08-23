package com.netless.network

import com.netless.common.NodeId
import kotlin.test.Test
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

}
