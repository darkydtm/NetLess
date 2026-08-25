package com.netless.network

import com.netless.common.NodeId
import com.netless.transport.TransportEndpoint
import com.netless.transport.TransportType
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

data class RouteHop(
	val nodeId: NodeId,
	val nextNodeId: NodeId,
	val transport: TransportType,
	val endpoint: TransportEndpoint,
	val metrics: RouteMetrics,
	val expiresAtMillis: Long,
) : Serializable {
	init {
		require(expiresAtMillis >= 0L) { "expiresAtMillis must be non-negative" }
		require(endpoint.nodeId == nextNodeId) { "endpoint must identify nextNodeId" }
	}
}

class Route(
	nodes: List<NodeId>,
	val metrics: RouteMetrics,
	val expiresAtMillis: Long = Long.MAX_VALUE,
	hops: List<RouteHop> = emptyList(),
) : Serializable {
	private val nodeValues = nodes.toMutableList()
	val nodes: List<NodeId> = Collections.unmodifiableList(nodeValues)
	val hops: List<RouteHop> = Collections.unmodifiableList(hops.toList())

	init {
		require(nodeValues.isNotEmpty()) { "Route must contain at least one node" }
		require(nodeValues.distinct().size == nodeValues.size) { "Route must not contain cycles" }
		require(expiresAtMillis >= 0L) { "expiresAtMillis must be non-negative" }
		require(hops.isEmpty() || hops.size == nodeValues.size - 1) { "hops must match route edges" }
		hops.forEachIndexed { index, hop ->
			require(hop.nodeId == nodeValues[index] && hop.nextNodeId == nodeValues[index + 1]) {
				"hop must match route edge"
			}
		}
	}

	fun copy(nodes: List<NodeId> = this.nodes, metrics: RouteMetrics = this.metrics): Route =
		Route(nodes, metrics, expiresAtMillis, hops)

	fun copy(
		nodes: List<NodeId> = this.nodes,
		metrics: RouteMetrics = this.metrics,
		expiresAtMillis: Long,
	): Route = Route(nodes, metrics, expiresAtMillis, hops)

	operator fun component1(): List<NodeId> = nodes

	operator fun component2(): RouteMetrics = metrics

	override fun equals(other: Any?): Boolean =
		other is Route && nodes == other.nodes && metrics == other.metrics && expiresAtMillis == other.expiresAtMillis && hops == other.hops

	override fun hashCode(): Int = (((31 * nodes.hashCode() + metrics.hashCode()) * 31) + expiresAtMillis.hashCode()) * 31 + hops.hashCode()

	override fun toString(): String = "Route(nodes=$nodes, metrics=$metrics, expiresAtMillis=$expiresAtMillis, hops=$hops)"
}
