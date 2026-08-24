package com.netless.transport

import com.netless.common.TrafficClass
import java.nio.ByteBuffer

data class AudioPacket(val sequence: Long, val timestampMillis: Long, val pcm: ByteArray) {
	fun encode(): ByteArray = ByteBuffer.allocate(16 + pcm.size).putLong(sequence).putLong(timestampMillis).put(pcm).array()

	companion object {
		fun decode(bytes: ByteArray): AudioPacket {
			require(bytes.size >= 16) { "audio packet is too short" }
			val buffer = ByteBuffer.wrap(bytes)
			return AudioPacket(buffer.long, buffer.long, ByteArray(buffer.remaining()).also(buffer::get))
		}
	}

	val trafficClass = TrafficClass.Realtime
}
