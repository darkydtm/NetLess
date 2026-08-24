package com.netless.network

import com.netless.common.NodeId
import com.netless.common.PacketId
import com.netless.common.TransferPolicy
import java.util.Collections

data class RelayPacket(
	val packetId: PacketId,
	val destination: NodeId,
	val payload: ByteArray,
	val ttl: Long,
	val hops: Int,
) {
	init {
		require(ttl >= 0) { "ttl must not be negative" }
		require(hops >= 0) { "hops must not be negative" }
	}
}

enum class RelayDecision {
	Relayed,
	Duplicate,
	Expired,
	MaxHops,
}

class MeshRelayNode(val nodeId: NodeId) {
	private val seen = HashSet<PacketId>()
	private val relayQueue = ArrayList<RelayPacket>()
	val relayed: List<RelayPacket>
		get() = Collections.unmodifiableList(relayQueue.toList())

	@Synchronized
	fun handle(packet: RelayPacket, policy: TransferPolicy): RelayDecision {
		if (!seen.add(packet.packetId)) return RelayDecision.Duplicate
		if (packet.ttl <= 0) return RelayDecision.Expired
		if (!policy.relayEnabled || packet.hops >= policy.maxHops) return RelayDecision.MaxHops
		relayQueue += packet.copy(ttl = packet.ttl - 1, hops = packet.hops + 1)
		return RelayDecision.Relayed
	}

	@Synchronized
	fun acknowledge(packetId: PacketId) {
		relayQueue.removeIf { it.packetId == packetId }
	}
}
