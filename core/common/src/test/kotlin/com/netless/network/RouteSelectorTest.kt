package com.netless.network

import com.netless.common.NodeId
import com.netless.common.TransferMode
import com.netless.common.TransferPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RouteSelectorTest {
	private val selector = RouteSelector()

	@Test
	fun speedPrefersDirectRouteBeforeMeshMetrics() {
		val direct = route(
			"A",
			"C",
			metrics = RouteMetrics(bandwidth = 1.0, latency = 100.0, energyCost = 100.0, availability = 0.5),
		)
		val mesh = route(
			"A",
			"B",
			"C",
			metrics = RouteMetrics(bandwidth = 100.0, latency = 1.0, energyCost = 1.0, availability = 1.0),
		)

		assertEquals(direct, selector.select(listOf(mesh, direct), TransferPolicy(mode = TransferMode.Speed)))
	}

	@Test
	fun balancedScoringPrefersTheStrongestOverallRoute() {
		val balanced = route(
			"A",
			"B",
			metrics = RouteMetrics(bandwidth = 10.0, latency = 2.0, energyCost = 2.0, availability = 0.9),
		)
		val weak = route(
			"A",
			"C",
			metrics = RouteMetrics(bandwidth = 2.0, latency = 10.0, energyCost = 10.0, availability = 0.5),
		)

		assertEquals(balanced, selector.select(listOf(weak, balanced), TransferPolicy()))
	}

	@Test
	fun coveragePrefersTheLongestRouteWithinTheHopLimit() {
		val direct = route("A", "C")
		val mesh = route("A", "B", "C")

		assertEquals(
			mesh,
			selector.select(
				listOf(direct, mesh),
				TransferPolicy(mode = TransferMode.Coverage, maxHops = 2),
			),
		)
	}

	@Test
	fun expiredRoutesAreIgnored() {
		val expired = route(
			"A",
			"B",
			metrics = RouteMetrics(bandwidth = 100.0, latency = 1.0, energyCost = 1.0, availability = 0.0),
		)
		val available = route("A", "C")

		assertEquals(available, selector.select(listOf(expired, available), TransferPolicy(mode = TransferMode.Speed)))
	}

	@Test
	fun positivelyAvailableRoutesWithExpiredTimestampsAreIgnored() {
		val deterministicSelector = RouteSelector { 1_000L }
		val expired = route(
			"A",
			"B",
			metrics = RouteMetrics(bandwidth = 100.0, latency = 1.0, energyCost = 1.0, availability = 1.0),
			expiresAtMillis = 1_000L,
		)
		val available = route("A", "C", expiresAtMillis = 1_001L)

		assertEquals(available, deterministicSelector.select(listOf(expired, available), TransferPolicy(mode = TransferMode.Speed)))
	}

	@Test
	fun returnsNullWhenEveryRouteIsExpired() {
		val expired = route(
			"A",
			"B",
			metrics = RouteMetrics(bandwidth = 1.0, latency = 1.0, energyCost = 1.0, availability = 0.0),
		)

		assertNull(selector.select(listOf(expired), TransferPolicy()))
	}

	@Test
	fun rejectsCyclicRoutes() {
		assertFailsWith<IllegalArgumentException> {
			Route(listOf(NodeId("A"), NodeId("B"), NodeId("A")), RouteMetrics(1.0, 1.0, 1.0, 1.0))
		}
	}

	@Test
	fun configurableHopLimitFiltersLongerRoutes() {
		val direct = route("A", "C")
		val mesh = route("A", "B", "C")

		assertEquals(direct, selector.select(listOf(mesh, direct), TransferPolicy(maxHops = 1)))
		assertNull(selector.select(listOf(mesh), TransferPolicy(maxHops = 1)))
	}

	@Test
	fun coverageRejectsRoutesBeyondMaxHops() {
		val overLimit = route("A", "B", "C")

		assertNull(
			selector.select(
				listOf(overLimit),
				TransferPolicy(mode = TransferMode.Coverage, maxHops = 1),
			),
		)
	}

	private fun route(
		vararg nodeValues: String,
		metrics: RouteMetrics = RouteMetrics(1.0, 1.0, 1.0, 1.0),
		expiresAtMillis: Long = Long.MAX_VALUE,
	): Route = Route(nodeValues.map(::NodeId), metrics, expiresAtMillis)
}
