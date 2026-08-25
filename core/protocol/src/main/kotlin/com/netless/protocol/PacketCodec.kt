package com.netless.protocol

import com.netless.common.NodeId
import com.netless.common.PacketId
import com.netless.common.ProfileId
import com.netless.common.TrafficClass
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

object VersionedPacketCodec {
	private const val MAX_STRING_BYTES = 65_536
	private const val MAX_BINARY_BYTES = 16 * 1024 * 1024
	private const val MAX_RECIPIENTS = 1_024

	fun encode(packet: PacketEnvelope, nowMillis: Long = System.currentTimeMillis()): ByteArray {
		checkVersion(packet.version)
		require(packet.expiresAtEpochMillis >= nowMillis) { "Packet has expired" }
		return ByteArrayOutputStream().use { output ->
			DataOutputStream(output).use { data ->
				data.writeInt(packet.version)
				data.writeLong(packet.createdAtEpochMillis)
				data.writeLong(packet.expiresAtEpochMillis)
				writeForwarding(data, packet.forwarding)
				writeContent(data, packet.content)
			}
			output.toByteArray()
		}
	}

	fun decode(bytes: ByteArray, nowMillis: Long = System.currentTimeMillis()): PacketEnvelope {
		require(bytes.isNotEmpty()) { "Packet bytes must not be empty" }
		try {
			DataInputStream(ByteArrayInputStream(bytes)).use { input ->
				val version = input.readInt()
				checkVersion(version)
				val createdAt = input.readLong()
				val expiresAt = input.readLong()
				val packet = PacketEnvelope(readForwarding(input), readContent(input), version, createdAt, expiresAt)
				require(expiresAt >= nowMillis) { "Packet has expired" }
				require(input.available() == 0) { "Trailing packet bytes" }
				packet
			}
		} catch (error: UnsupportedProtocolVersionException) {
			throw error
		} catch (error: EOFException) {
			throw IllegalArgumentException("Truncated packet", error)
		} catch (error: CharacterCodingException) {
			throw IllegalArgumentException("Invalid UTF-8 string", error)
		} catch (error: NegativeArraySizeException) {
			throw IllegalArgumentException("Invalid packet length", error)
		}
	}

	private fun writeForwarding(output: DataOutputStream, envelope: ForwardingEnvelope) {
		writeString(output, envelope.packetId.value)
		writeString(output, envelope.finalNodeId.value)
		output.writeBoolean(envelope.nextHop != null)
		if (envelope.nextHop != null) writeString(output, envelope.nextHop.value)
		output.writeInt(envelope.hopCount)
		output.writeLong(envelope.ttl)
		output.writeInt(envelope.trafficClass.ordinal)
		writeBytes(output, envelope.perHopIntegrity)
	}

	private fun writeContent(output: DataOutputStream, envelope: ContentEnvelope) {
		writeString(output, envelope.eventId)
		writeString(output, envelope.senderProfileId.value)
		require(envelope.recipients.size <= MAX_RECIPIENTS) { "Too many recipients" }
		output.writeInt(envelope.recipients.size)
		envelope.recipients.forEach { writeString(output, it.value) }
		writeBytes(output, envelope.encryptedPayload)
		writeBytes(output, envelope.senderSignature)
	}

	private fun readForwarding(input: DataInputStream): ForwardingEnvelope {
		val packetId = PacketId(readString(input))
		val finalNodeId = NodeId(readString(input))
		val nextHop = when (val flag = input.readUnsignedByte()) {
			0 -> null
			1 -> NodeId(readString(input))
			else -> throw IllegalArgumentException("Invalid next-hop flag: $flag")
		}
		val hopCount = input.readInt()
		val ttl = input.readLong()
		val trafficClass = TrafficClass.entries.getOrNull(input.readInt())
			?: throw IllegalArgumentException("Invalid traffic class")
		return ForwardingEnvelope(packetId, finalNodeId, nextHop, hopCount, ttl, trafficClass, readBytes(input))
	}

	private fun readContent(input: DataInputStream): ContentEnvelope {
		val eventId = readString(input)
		val senderProfileId = ProfileId(readString(input))
		val count = input.readInt()
		if (count !in 1..MAX_RECIPIENTS) throw IllegalArgumentException("Invalid recipient count")
		return ContentEnvelope(
			eventId,
			senderProfileId,
			List(count) { ProfileId(readString(input)) },
			readBytes(input),
			readBytes(input),
		)
	}

	private fun writeString(output: DataOutputStream, value: String) {
		val bytes = value.toByteArray(StandardCharsets.UTF_8)
		if (bytes.isEmpty() || bytes.size > MAX_STRING_BYTES) throw IllegalArgumentException("Invalid string length")
		output.writeInt(bytes.size)
		output.write(bytes)
	}

	private fun readString(input: DataInputStream): String {
		val bytes = readBytes(input, MAX_STRING_BYTES, "string")
		return StandardCharsets.UTF_8.newDecoder()
			.onMalformedInput(CodingErrorAction.REPORT)
			.onUnmappableCharacter(CodingErrorAction.REPORT)
			.decode(ByteBuffer.wrap(bytes)).toString()
	}

	private fun writeBytes(output: DataOutputStream, value: ByteArray) {
		if (value.isEmpty() || value.size > MAX_BINARY_BYTES) throw IllegalArgumentException("Invalid binary length")
		output.writeInt(value.size)
		output.write(value)
	}

	private fun readBytes(input: DataInputStream): ByteArray = readBytes(input, MAX_BINARY_BYTES, "binary")

	private fun readBytes(input: DataInputStream, maximum: Int, name: String): ByteArray {
		val length = input.readInt()
		if (length !in 1..maximum) throw IllegalArgumentException("Invalid $name length")
		return ByteArray(length).also(input::readFully)
	}

	private fun checkVersion(version: Int) {
		if (version != CURRENT_PROTOCOL_VERSION) {
			throw UnsupportedProtocolVersionException("Unsupported protocol version: $version")
		}
	}
}
