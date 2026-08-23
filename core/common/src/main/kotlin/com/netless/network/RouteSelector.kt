package com.netless.network

import com.netless.common.TransferMode
import com.netless.common.TransferPolicy

class RouteSelector {
	fun select(routes: List<Route>, policy: TransferPolicy): Route? {
		val candidates = routes.filter { route ->
			route.nodes.size - 1 <= policy.maxHops &&
			route.metrics.availability > 0.0
		}

		return candidates.minWithOrNull(comparator(policy.mode))
	}

	private fun comparator(mode: TransferMode): Comparator<Route> = Comparator { left, right ->
		when (mode) {
			TransferMode.Speed -> compareSpeed(left, right)
			TransferMode.Balanced -> compareBalanced(left, right)
			TransferMode.Coverage -> compareCoverage(left, right)
		}
	}

	private fun compareSpeed(left: Route, right: Route): Int {
		var result = hops(left).compareTo(hops(right))
		if (result == 0) result = right.metrics.bandwidth.compareTo(left.metrics.bandwidth)
		if (result == 0) result = left.metrics.latency.compareTo(right.metrics.latency)
		if (result == 0) result = left.metrics.energyCost.compareTo(right.metrics.energyCost)
		if (result == 0) result = right.metrics.availability.compareTo(left.metrics.availability)
		return if (result == 0) comparePaths(left, right) else result
	}

	private fun compareBalanced(left: Route, right: Route): Int {
		var result = balancedScore(right).compareTo(balancedScore(left))
		if (result == 0) result = hops(left).compareTo(hops(right))
		return if (result == 0) comparePaths(left, right) else result
	}

	private fun compareCoverage(left: Route, right: Route): Int {
		var result = hops(right).compareTo(hops(left))
		if (result == 0) result = right.metrics.availability.compareTo(left.metrics.availability)
		if (result == 0) result = right.metrics.bandwidth.compareTo(left.metrics.bandwidth)
		if (result == 0) result = left.metrics.latency.compareTo(right.metrics.latency)
		if (result == 0) result = left.metrics.energyCost.compareTo(right.metrics.energyCost)
		return if (result == 0) comparePaths(left, right) else result
	}

	private fun balancedScore(route: Route): Double {
		val metrics = route.metrics
		return 0.35 * increasing(metrics.bandwidth) +
			0.25 * decreasing(metrics.latency) +
			0.15 * decreasing(metrics.energyCost) +
			0.20 * metrics.availability +
			0.05 * decreasing(hops(route).toDouble())
	}

	private fun increasing(value: Double): Double = value / (1.0 + value)

	private fun decreasing(value: Double): Double = 1.0 / (1.0 + value)

	private fun hops(route: Route): Int = route.nodes.size - 1

	private fun comparePaths(left: Route, right: Route): Int {
		val commonSize = minOf(left.nodes.size, right.nodes.size)
		for (index in 0 until commonSize) {
			val result = left.nodes[index].value.compareTo(right.nodes[index].value)
			if (result != 0) return result
		}
		return left.nodes.size.compareTo(right.nodes.size)
	}
}
