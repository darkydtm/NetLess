package com.netless.network

import com.netless.common.NodeId
import com.netless.common.TransferPolicy
import com.netless.transport.TransportPolicy
import com.netless.transport.TransportSelectionMode

class RouteEngine(private val selector: RouteSelector = RouteSelector()) {
	fun select(destination: NodeId, graph: RouteGraph, policy: TransportPolicy, nowMillis: Long): Route? {
		val routes = graph.routesTo(destination, nowMillis).filter { route ->
			route.hops.isNotEmpty() && route.hops.all { it.expiresAtMillis > nowMillis } &&
			(policy.relayAllowed || route.hops.size == 1) &&
			(policy.strictTransport == null || route.hops.all { it.transport == policy.strictTransport })
		}
		if (routes.isEmpty()) return null

		if (policy.mode == TransportSelectionMode.Preferred) {
			for (transport in policy.preferences) {
				val preferred = routes.filter { it.hops.first().transport == transport }
				selector.select(preferred, TransferPolicy(maxHops = graph.hopLimit), nowMillis)?.let { return it }
			}
			return null
		}
		return selector.select(routes, TransferPolicy(maxHops = graph.hopLimit), nowMillis)
	}
}
