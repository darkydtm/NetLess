package com.netless.protocol

import com.netless.common.NodeId
import com.netless.common.PacketId
import com.netless.common.ProfileId
import com.netless.common.TrafficClass
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProtocolContractsTest {
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
	)

	@Test
	fun packetRoundTripsThroughCodec() {
		val decoded = BinaryPacketCodec().decode(BinaryPacketCodec().encode(packet))

		assertEquals(packet, decoded)
		assertContentEquals(packet.forwarding.perHopIntegrity, decoded.forwarding.perHopIntegrity)
		assertContentEquals(packet.content.encryptedPayload, decoded.content.encryptedPayload)
	}

	@Test
	fun rejectsUnsupportedVersion() {
		val bytes = BinaryPacketCodec().encode(packet)
		bytes[7] = (CURRENT_PROTOCOL_VERSION + 1).toByte()

		assertFailsWith<UnsupportedProtocolVersionException> { BinaryPacketCodec().decode(bytes) }
	}

	@Test
	fun preservesEveryTrafficClass() {
		val codec = BinaryPacketCodec()

		TrafficClass.entries.forEach { trafficClass ->
			val decoded = codec.decode(
				codec.encode(
					packet.copy(forwarding = packet.forwarding.copy(trafficClass = trafficClass)),
				),
			)
			assertEquals(trafficClass, decoded.forwarding.trafficClass)
		}
	}

	@Test
	fun rejectsInvalidEnvelopeValues() {
		assertFailsWith<IllegalArgumentException> {
			ForwardingEnvelope(
				packetId = PacketId("packet-1"),
				finalNodeId = NodeId("node-c"),
				nextHop = NodeId("node-b"),
				hopCount = -1,
				ttl = 60,
				trafficClass = TrafficClass.Reliable,
				perHopIntegrity = byteArrayOf(1),
			)
		}
		assertFailsWith<IllegalArgumentException> {
			packet.content.copy(recipients = emptyList())
		}
	}
}
