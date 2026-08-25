package com.netless.protocol

import com.netless.common.NodeId
import com.netless.common.PacketId
import com.netless.common.ProfileId
import com.netless.common.TrafficClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.io.Serializable

class PacketCodecTest {
	private val packet = PacketEnvelope(
		forwarding = ForwardingEnvelope(
			packetId = PacketId("packet-1"),
			finalNodeId = NodeId("node-c"),
			nextHop = NodeId("node-b"),
			hopCount = 1,
			ttl = 60,
			trafficClass = TrafficClass.Reliable,
			perHopIntegrity = byteArrayOf(1, 2, 3),
		),
		content = ContentEnvelope(
			eventId = "event-1",
			senderProfileId = ProfileId("profile-1"),
			recipients = listOf(ProfileId("profile-2")),
			encryptedPayload = byteArrayOf(4, 5, 6),
			senderSignature = byteArrayOf(7, 8, 9),
		),
		createdAtEpochMillis = 20L,
		expiresAtEpochMillis = Long.MAX_VALUE,
	)

	@Test
	fun `packet codec preserves forwarding and encrypted content`() {
		assertEquals(packet, PacketCodec.decode(PacketCodec.encode(packet)))
	}

	@Test
	fun `packet rejects expiry before creation`() {
		assertFailsWith<IllegalArgumentException> {
			PacketEnvelope(packet.forwarding, packet.content, createdAtEpochMillis = 20L, expiresAtEpochMillis = 19L)
		}
	}

	@Test
	fun `delivery receipt is serializable`() {
		assertTrue(DeliveryReceipt(PacketId("packet-1"), DeliveryState.Delivered, NodeId("node-a"), 20L) is Serializable)
	}
}
