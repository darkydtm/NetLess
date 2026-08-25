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
	private const val RECEIPT = 3
	private const val MAX_FRAME = 4 * 1024 * 1024
	private const val MAX_TEXT = 1024

	fun forward(packet: ByteArray): ByteArray = frame(FORWARD, packet)

	fun acknowledgement(ack: HopAcknowledgement): ByteArray = ByteArrayOutputStream().also {
		DataOutputStream(it).apply {
			writeInt(MAGIC); writeInt(VERSION); writeInt(ACK)
			writeUTF(ack.packetId.value); writeUTF(ack.nodeId.value); writeInt(ack.status); writeBoolean(ack.finalDelivery)
		}
	}.toByteArray()

	fun receipt(receipt: DeliveryReceipt): ByteArray = ByteArrayOutputStream().also {
		DataOutputStream(it).apply { writeInt(MAGIC); writeInt(VERSION); writeInt(RECEIPT); writeUTF(receipt.packetId.value); writeUTF(receipt.nodeId.value); writeInt(receipt.state.ordinal) }
	}.toByteArray()

	fun decode(bytes: ByteArray): ControlFrame {
		require(bytes.size <= MAX_FRAME) { "control frame too large" }
		val input = DataInputStream(ByteArrayInputStream(bytes))
		val frame = when {
			input.readInt() != MAGIC -> error("invalid control frame")
			input.readInt() != VERSION -> error("unsupported control version")
			else -> when (input.readInt()) {
				FORWARD -> input.readInt().let { size -> require(size in 0..MAX_FRAME && size <= input.available()) { "invalid control payload length" }; Forward(input.readNBytes(size)) }
				ACK -> input.readUTF().let { packetId -> require(packetId.length <= MAX_TEXT); val node = input.readUTF(); require(node.length <= MAX_TEXT); val status = input.readInt(); require(status == 0 || status == 1); Acknowledgement(HopAcknowledgement(PacketId(packetId), NodeId(node), status == 0, status, input.readBoolean())) }
				RECEIPT -> { val packet = input.readUTF(); val node = input.readUTF(); val state = input.readInt(); require(state in DeliveryState.values().indices); Receipt(DeliveryReceipt(PacketId(packet), DeliveryState.values()[state], NodeId(node), 0L)) }
				else -> error("unknown control frame")
			}
		}
		require(input.available() == 0) { "trailing control bytes" }
		return frame
	}

	private fun frame(type: Int, payload: ByteArray) = ByteArrayOutputStream().also {
		DataOutputStream(it).apply { writeInt(MAGIC); writeInt(VERSION); writeInt(type); writeInt(payload.size); write(payload) }
	}.toByteArray()
}

sealed interface ControlFrame
data class Forward(val packet: ByteArray) : ControlFrame
data class Acknowledgement(val value: HopAcknowledgement) : ControlFrame
data class Receipt(val value: DeliveryReceipt) : ControlFrame
