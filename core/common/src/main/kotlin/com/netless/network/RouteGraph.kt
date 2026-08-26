package com.netless.network

import com.netless.common.NodeId
import com.netless.common.TransferPolicy

class RouteGraph(hops: List<RouteHop>, private val maxHops: Int = TransferPolicy().maxHops) {
	private val allHops = hops
	private val outgoing = hops.groupBy { it.nodeId }

	init {
		require(maxHops >= 0) { "maxHops must not be negative" }
	}

	fun routesTo(destination: NodeId, nowMillis: Long): List<Route> {
		val activeHops = outgoing.values.flatten().filter { it.expiresAtMillis > nowMillis }
		val nodes = allHops.flatMap { listOf(it.nodeId, it.nextNodeId) }.toSet()
		val components = nodesByComponent(allHops, nodes)
		val starts = components.flatMap { component ->
			val incoming = activeHops.filter { it.nextNodeId in component }.map { it.nextNodeId }.toSet()
			val roots = component.filter { it !in incoming }
			(if (roots.isEmpty() && maxHops == 1) emptySet() else if (roots.isEmpty()) component else roots).sortedBy { it.value }
		}.sortedBy { it.value }
		return starts.flatMap { start ->
			val component = components.first { start in it }
			val componentExpiry = allHops
				.filter { it.nodeId in component && it.nextNodeId in component }
				.minOfOrNull { it.expiresAtMillis } ?: Long.MAX_VALUE
			routesFrom(start, destination, nowMillis)
				.map { it.copy(expiresAtMillis = minOf(it.expiresAtMillis, componentExpiry)) }
		}
	}

	val hopLimit: Int
		get() = maxHops

	private fun nodesByComponent(activeHops: List<RouteHop>, nodes: Set<NodeId>): List<Set<NodeId>> {
		val adjacent = activeHops.flatMap { hop ->
			listOf(hop.nodeId to hop.nextNodeId, hop.nextNodeId to hop.nodeId)
		}.groupBy({ it.first }, { it.second })
		val remaining = nodes.toMutableSet()
		val components = mutableListOf<Set<NodeId>>()
		while (remaining.isNotEmpty()) {
			val component = mutableSetOf<NodeId>()
			val queue = ArrayDeque<NodeId>()
			queue += remaining.first()
			while (queue.isNotEmpty()) {
				val node = queue.removeFirst()
				if (!component.add(node)) continue
				remaining.remove(node)
				adjacent[node].orEmpty().forEach { queue += it }
			}
			components += component
		}
		return components
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
				availability = (hops.fold(1.0) { total, hop -> total * hop.metrics.availability } * 100).toInt() / 100.0,
			)
			return Route(nodes, metrics, hops.minOfOrNull { it.expiresAtMillis } ?: Long.MAX_VALUE, hops)
		}
	}
}
