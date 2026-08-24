package com.netless.network

import com.netless.common.NodeId
import com.netless.common.PacketId
import com.netless.common.TransferPolicy
import kotlin.test.Test
import kotlin.test.assertEquals

class MeshRelayTest {
	private val node = MeshRelayNode(NodeId("relay"))

	@Test
	fun relaysPacketOnceAndSuppressesDuplicate() {
		val packet = RelayPacket(PacketId("packet"), NodeId("destination"), byteArrayOf(1), ttl = 3, hops = 0)

		assertEquals(RelayDecision.Relayed, node.handle(packet, TransferPolicy()))
		assertEquals(RelayDecision.Duplicate, node.handle(packet, TransferPolicy()))
		assertEquals(packet.packetId, node.relayed.single().packetId)
		assertEquals(2, node.relayed.single().ttl)
	}

	@Test
	fun rejectsExpiredAndOverHopPackets() {
		assertEquals(
			RelayDecision.Expired,
			node.handle(RelayPacket(PacketId("expired"), NodeId("destination"), byteArrayOf(1), 0, 0), TransferPolicy()),
		)
		assertEquals(
			RelayDecision.MaxHops,
			node.handle(RelayPacket(PacketId("hops"), NodeId("destination"), byteArrayOf(1), 3, 2), TransferPolicy(maxHops = 2)),
		)
	}
}
