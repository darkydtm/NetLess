package com.netless.network

import com.netless.common.NodeId
import com.netless.common.TransferPolicy

class RouteGraph(hops: List<RouteHop>, private val maxHops: Int = TransferPolicy().maxHops) {
	private val outgoing = hops.groupBy { it.nodeId }

	init {
		require(maxHops >= 0) { "maxHops must not be negative" }
	}

	fun routesTo(destination: NodeId, nowMillis: Long): List<Route> {
		val starts = outgoing.keys.filter { node -> outgoing.none { (_, edges) -> edges.any { it.nextNodeId == node } } }.sortedBy { it.value }
		return starts.flatMap { start -> routesFrom(start, destination, nowMillis) }
	}

	private fun routesFrom(start: NodeId, destination: NodeId, nowMillis: Long): List<Route> {
		val routes = mutableListOf<Route>()
		val queue = ArrayDeque<Path>()
		queue += Path(listOf(start), emptyList())

		while (queue.isNotEmpty()) {
			val path = queue.removeFirst()
			if (path.nodes.last() == destination) {
				routes += path.toRoute()
				continue
			}
			if (path.hops.size >= maxHops) continue
			for (hop in outgoing[path.nodes.last()].orEmpty().asSequence()
				.filter { it.expiresAtMillis > nowMillis }
				.filter { it.nextNodeId !in path.nodes }
				.sortedBy { it.nextNodeId.value }) {
				queue += Path(path.nodes + hop.nextNodeId, path.hops + hop)
			}
		}
		return routes
	}

	private data class Path(val nodes: List<NodeId>, val hops: List<RouteHop>) {
		fun toRoute(): Route {
			val metrics = RouteMetrics(
				bandwidth = hops.minOfOrNull { it.metrics.bandwidth } ?: 0.0,
				latency = hops.sumOf { it.metrics.latency },
				energyCost = hops.sumOf { it.metrics.energyCost },
				availability = hops.fold(1.0) { total, hop -> total * hop.metrics.availability },
			)
			return Route(nodes, metrics, hops.minOfOrNull { it.expiresAtMillis } ?: Long.MAX_VALUE, hops)
		}
	}
}
