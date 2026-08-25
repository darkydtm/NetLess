package com.netless.protocol

import com.netless.common.NodeId
import com.netless.common.PacketId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

object ControlCodec {
	private const val MAGIC = 0x4E4C4331
	private const val VERSION = 1
	private const val FORWARD = 1
	private const val ACK = 2

	fun forward(packet: ByteArray): ByteArray = frame(FORWARD, packet)

	fun acknowledgement(ack: HopAcknowledgement): ByteArray = ByteArrayOutputStream().also {
		DataOutputStream(it).apply {
			writeInt(MAGIC); writeInt(VERSION); writeInt(ACK)
			writeUTF(ack.packetId.value); writeUTF(ack.nodeId.value); writeInt(ack.status)
		}
	}.toByteArray()

	fun decode(bytes: ByteArray): ControlFrame = DataInputStream(ByteArrayInputStream(bytes)).use {
		require(it.readInt() == MAGIC) { "invalid control frame" }
		require(it.readInt() == VERSION) { "unsupported control version" }
		when (it.readInt()) {
			FORWARD -> Forward(it.readNBytes(it.readInt()))
			ACK -> it.readUTF().let { packetId ->
				Acknowledgement(HopAcknowledgement(PacketId(packetId), NodeId(it.readUTF()), it.readInt() == 0))
			}
			else -> error("unknown control frame")
		}
	}

	private fun frame(type: Int, payload: ByteArray) = ByteArrayOutputStream().also {
		DataOutputStream(it).apply { writeInt(MAGIC); writeInt(VERSION); writeInt(type); writeInt(payload.size); write(payload) }
	}.toByteArray()
}

sealed interface ControlFrame
data class Forward(val packet: ByteArray) : ControlFrame
data class Acknowledgement(val value: HopAcknowledgement) : ControlFrame
