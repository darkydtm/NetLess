package com.netless.network

import com.netless.common.NodeId
import java.io.Serializable
import java.util.Collections

data class RouteMetrics(
	val bandwidth: Double,
	val latency: Double,
	val energyCost: Double,
	val availability: Double,
) : Serializable {
	init {
		require(bandwidth.isFinite() && bandwidth >= 0.0) { "bandwidth must be finite and non-negative" }
		require(latency.isFinite() && latency >= 0.0) { "latency must be finite and non-negative" }
		require(energyCost.isFinite() && energyCost >= 0.0) { "energyCost must be finite and non-negative" }
		require(availability.isFinite() && availability in 0.0..1.0) { "availability must be between 0 and 1" }
	}
}

class Route(
	nodes: List<NodeId>,
	val metrics: RouteMetrics,
) : Serializable {
	private val nodeValues = nodes.toMutableList()
	val nodes: List<NodeId> = Collections.unmodifiableList(nodeValues)

	init {
		require(nodeValues.isNotEmpty()) { "Route must contain at least one node" }
		require(nodeValues.distinct().size == nodeValues.size) { "Route must not contain cycles" }
	}

	override fun equals(other: Any?): Boolean =
		other is Route && nodes == other.nodes && metrics == other.metrics

	override fun hashCode(): Int = 31 * nodes.hashCode() + metrics.hashCode()

	override fun toString(): String = "Route(nodes=$nodes, metrics=$metrics)"
}
