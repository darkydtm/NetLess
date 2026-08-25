package com.netless.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.nio.charset.CodingErrorAction
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets

class UnsupportedProtocolVersionException(message: String) : IllegalArgumentException(message)

class BinaryPacketCodec {
	private companion object {
		const val MAGIC = 0x4E4C5031
		const val MAX_STRING_BYTES = 65_536
		const val MAX_BINARY_BYTES = 16 * 1024 * 1024
		const val MAX_RECIPIENTS = 1_024
	}

	fun encode(packet: PacketEnvelope): ByteArray {
		checkVersion(packet.version)
		val output = ByteArrayOutputStream()
		DataOutputStream(output).use { data ->
			data.writeInt(MAGIC)
			data.writeInt(packet.version)
			writeForwarding(data, packet.forwarding)
			writeContent(data, packet.content)
		}
		return output.toByteArray()
	}

	fun decode(bytes: ByteArray): PacketEnvelope {
		require(bytes.isNotEmpty()) { "Packet bytes must not be empty" }
		try {
			DataInputStream(ByteArrayInputStream(bytes)).use { data ->
				if (data.readInt() != MAGIC) {
					throw IllegalArgumentException("Invalid packet magic")
				}
				val version = data.readInt()
				checkVersion(version)
				val forwarding = readForwarding(data)
				val content = readContent(data)
				if (data.available() != 0) {
					throw IllegalArgumentException("Trailing packet bytes")
				}
				return PacketEnvelope(forwarding, content, version)
			}
		} catch (error: UnsupportedProtocolVersionException) {
			throw error
		} catch (error: EOFException) {
			throw IllegalArgumentException("Truncated packet", error)
		} catch (error: CharacterCodingException) {
			throw IllegalArgumentException("Invalid UTF-8 string", error)
		} catch (error: IOException) {
			throw IllegalArgumentException("Invalid packet encoding", error)
		} catch (error: NegativeArraySizeException) {
			throw IllegalArgumentException("Invalid packet length", error)
		}
	}

	private fun writeForwarding(output: DataOutputStream, envelope: ForwardingEnvelope) {
		writeString(output, envelope.packetId.value)
		writeString(output, envelope.finalNodeId.value)
		output.writeBoolean(envelope.nextHop != null)
		if (envelope.nextHop != null) {
			writeString(output, envelope.nextHop.value)
		}
		output.writeInt(envelope.hopCount)
		output.writeLong(envelope.ttl)
		output.writeInt(envelope.trafficClass.ordinal)
		writeBytes(output, envelope.perHopIntegrity)
	}

	private fun writeContent(output: DataOutputStream, envelope: ContentEnvelope) {
		writeString(output, envelope.eventId)
		writeString(output, envelope.senderProfileId.value)
		if (envelope.recipients.size > MAX_RECIPIENTS) {
			throw IllegalArgumentException("Too many recipients")
		}
		output.writeInt(envelope.recipients.size)
		for (recipient in envelope.recipients) {
			writeString(output, recipient.value)
		}
		writeBytes(output, envelope.encryptedPayload)
		writeBytes(output, envelope.senderSignature)
	}

	private fun readForwarding(input: DataInputStream): ForwardingEnvelope {
		val packetId = com.netless.common.PacketId(readString(input))
		val finalNodeId = com.netless.common.NodeId(readString(input))
		val nextHop = when (val flag = input.readUnsignedByte()) {
			0 -> null
			1 -> com.netless.common.NodeId(readString(input))
			else -> throw IllegalArgumentException("Invalid next-hop flag: $flag")
		}
		val hopCount = input.readInt()
		val ttl = input.readLong()
		val trafficClass = enumValue(input.readInt(), com.netless.common.TrafficClass.entries, "traffic class")
		return ForwardingEnvelope(packetId, finalNodeId, nextHop, hopCount, ttl, trafficClass, readBytes(input))
	}

	private fun readContent(input: DataInputStream): ContentEnvelope {
		val eventId = readString(input)
		val senderProfileId = com.netless.common.ProfileId(readString(input))
		val recipientCount = input.readInt()
		if (recipientCount !in 1..MAX_RECIPIENTS) {
			throw IllegalArgumentException("Invalid recipient count")
		}
		val recipients = buildList(recipientCount) {
			repeat(recipientCount) {
				add(com.netless.common.ProfileId(readString(input)))
			}
		}
		return ContentEnvelope(eventId, senderProfileId, recipients, readBytes(input), readBytes(input))
	}

	private fun writeString(output: DataOutputStream, value: String) {
		val bytes = value.toByteArray(StandardCharsets.UTF_8)
		if (bytes.isEmpty() || bytes.size > MAX_STRING_BYTES) {
			throw IllegalArgumentException("Invalid string length")
		}
		output.writeInt(bytes.size)
		output.write(bytes)
	}

	private fun readString(input: DataInputStream): String {
		val bytes = readLength(input, MAX_STRING_BYTES, "string")
		return StandardCharsets.UTF_8.newDecoder()
			.onMalformedInput(CodingErrorAction.REPORT)
			.onUnmappableCharacter(CodingErrorAction.REPORT)
			.decode(java.nio.ByteBuffer.wrap(bytes))
			.toString()
	}

	private fun writeBytes(output: DataOutputStream, value: ByteArray) {
		if (value.isEmpty() || value.size > MAX_BINARY_BYTES) {
			throw IllegalArgumentException("Invalid binary length")
		}
		output.writeInt(value.size)
		output.write(value)
	}

	private fun readBytes(input: DataInputStream): ByteArray = readLength(input, MAX_BINARY_BYTES, "binary")

	private fun readLength(input: DataInputStream, maximum: Int, name: String): ByteArray {
		val length = input.readInt()
		if (length !in 1..maximum) {
			throw IllegalArgumentException("Invalid $name length")
		}
		return ByteArray(length).also(input::readFully)
	}

	private fun checkVersion(version: Int) {
		if (version != CURRENT_PROTOCOL_VERSION) {
			throw UnsupportedProtocolVersionException("Unsupported protocol version: $version")
		}
	}

	private fun <T> enumValue(ordinal: Int, values: List<T>, name: String): T =
		values.getOrNull(ordinal) ?: throw IllegalArgumentException("Invalid $name")
}
