package com.netless.transport

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class AudioPacketTest {
	@Test
	fun roundTripsPcmFrame() {
		val packet = AudioPacket(3, 42, byteArrayOf(1, 2, 3))
		val decoded = AudioPacket.decode(packet.encode())
		assertEquals(packet.sequence, decoded.sequence)
		assertEquals(packet.timestampMillis, decoded.timestampMillis)
		assertContentEquals(packet.pcm, decoded.pcm)
	}
}
