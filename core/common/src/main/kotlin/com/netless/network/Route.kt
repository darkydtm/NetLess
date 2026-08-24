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
	val expiresAtMillis: Long = Long.MAX_VALUE,
) : Serializable {
	private val nodeValues = nodes.toMutableList()
	val nodes: List<NodeId> = Collections.unmodifiableList(nodeValues)

	init {
		require(nodeValues.isNotEmpty()) { "Route must contain at least one node" }
		require(nodeValues.distinct().size == nodeValues.size) { "Route must not contain cycles" }
		require(expiresAtMillis >= 0L) { "expiresAtMillis must be non-negative" }
	}

	fun copy(nodes: List<NodeId> = this.nodes, metrics: RouteMetrics = this.metrics): Route =
		Route(nodes, metrics, expiresAtMillis)

	fun copy(
		nodes: List<NodeId> = this.nodes,
		metrics: RouteMetrics = this.metrics,
		expiresAtMillis: Long,
	): Route = Route(nodes, metrics, expiresAtMillis)

	operator fun component1(): List<NodeId> = nodes

	operator fun component2(): RouteMetrics = metrics

	override fun equals(other: Any?): Boolean =
		other is Route && nodes == other.nodes && metrics == other.metrics && expiresAtMillis == other.expiresAtMillis

	override fun hashCode(): Int = 31 * (31 * nodes.hashCode() + metrics.hashCode()) + expiresAtMillis.hashCode()

	override fun toString(): String = "Route(nodes=$nodes, metrics=$metrics, expiresAtMillis=$expiresAtMillis)"
}
