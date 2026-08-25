package com.netless.protocol

import com.netless.common.NodeId
import com.netless.common.PacketId
import com.netless.common.ProfileId
import com.netless.common.TrafficClass
import java.io.Serializable
import java.util.Collections

const val CURRENT_PROTOCOL_VERSION = 1

class ForwardingEnvelope(
	val packetId: PacketId,
	val finalNodeId: NodeId,
	nextHop: NodeId?,
	val hopCount: Int,
	val ttl: Long,
	val trafficClass: TrafficClass,
	perHopIntegrity: ByteArray,
	currentNodeId: NodeId = finalNodeId,
) : Serializable {
	private val perHopIntegrityValue = perHopIntegrity.copyOf()

	val nextHop: NodeId? = nextHop
	val currentNodeId: NodeId = currentNodeId
	val perHopIntegrity: ByteArray
		get() = perHopIntegrityValue.copyOf()

	init {
		require(hopCount >= 0) { "hopCount must not be negative" }
		require(ttl > 0) { "ttl must be positive" }
		require(perHopIntegrityValue.isNotEmpty()) { "perHopIntegrity must not be empty" }
	}

	fun copy(
		packetId: PacketId = this.packetId,
		currentNodeId: NodeId = this.currentNodeId,
		finalNodeId: NodeId = this.finalNodeId,
		nextHop: NodeId? = this.nextHop,
		hopCount: Int = this.hopCount,
		ttl: Long = this.ttl,
		trafficClass: TrafficClass = this.trafficClass,
		perHopIntegrity: ByteArray = this.perHopIntegrity,
	) = ForwardingEnvelope(
		packetId,
		finalNodeId,
		nextHop,
		hopCount,
		ttl,
		trafficClass,
		perHopIntegrity,
		currentNodeId,
	)

	override fun equals(other: Any?): Boolean =
		other is ForwardingEnvelope &&
			packetId == other.packetId &&
			currentNodeId == other.currentNodeId &&
			finalNodeId == other.finalNodeId &&
			nextHop == other.nextHop &&
			hopCount == other.hopCount &&
			ttl == other.ttl &&
			trafficClass == other.trafficClass &&
			perHopIntegrityValue.contentEquals(other.perHopIntegrityValue)

	override fun hashCode(): Int {
		var result = packetId.hashCode()
		result = 31 * result + currentNodeId.hashCode()
		result = 31 * result + finalNodeId.hashCode()
		result = 31 * result + (nextHop?.hashCode() ?: 0)
		result = 31 * result + hopCount
		result = 31 * result + ttl.hashCode()
		result = 31 * result + trafficClass.hashCode()
		result = 31 * result + perHopIntegrityValue.contentHashCode()
		return result
	}
}

class ContentEnvelope(
	eventId: String,
	val senderProfileId: ProfileId,
	recipients: List<ProfileId>,
	encryptedPayload: ByteArray,
	senderSignature: ByteArray,
) : Serializable {
	private val encryptedPayloadValue = encryptedPayload.copyOf()
	private val senderSignatureValue = senderSignature.copyOf()

	val eventId = eventId
	val recipients: List<ProfileId> = Collections.unmodifiableList(recipients.toMutableList())
	val encryptedPayload: ByteArray
		get() = encryptedPayloadValue.copyOf()
	val senderSignature: ByteArray
		get() = senderSignatureValue.copyOf()

	init {
		require(eventId.isNotBlank()) { "eventId must not be blank" }
		require(senderProfileId.value.isNotBlank()) { "senderProfileId must not be blank" }
		require(this.recipients.isNotEmpty()) { "recipients must not be empty" }
		require(this.recipients.distinct().size == this.recipients.size) { "recipients must be unique" }
		require(encryptedPayloadValue.isNotEmpty()) { "encryptedPayload must not be empty" }
		require(senderSignatureValue.isNotEmpty()) { "senderSignature must not be empty" }
	}

	fun copy(
		eventId: String = this.eventId,
		senderProfileId: ProfileId = this.senderProfileId,
		recipients: List<ProfileId> = this.recipients,
		encryptedPayload: ByteArray = this.encryptedPayload,
		senderSignature: ByteArray = this.senderSignature,
	) = ContentEnvelope(
		eventId,
		senderProfileId,
		recipients,
		encryptedPayload,
		senderSignature,
	)

	override fun equals(other: Any?): Boolean =
		other is ContentEnvelope &&
			eventId == other.eventId &&
			senderProfileId == other.senderProfileId &&
			recipients == other.recipients &&
			encryptedPayloadValue.contentEquals(other.encryptedPayloadValue) &&
			senderSignatureValue.contentEquals(other.senderSignatureValue)

	override fun hashCode(): Int {
		var result = eventId.hashCode()
		result = 31 * result + senderProfileId.hashCode()
		result = 31 * result + recipients.hashCode()
		result = 31 * result + encryptedPayloadValue.contentHashCode()
		result = 31 * result + senderSignatureValue.contentHashCode()
		return result
	}
}

data class PacketEnvelope(
	val forwarding: ForwardingEnvelope,
	val content: ContentEnvelope,
	val version: Int = CURRENT_PROTOCOL_VERSION,
	val createdAtEpochMillis: Long = 0L,
	val expiresAtEpochMillis: Long = Long.MAX_VALUE,
) : Serializable {
	init {
		require(version > 0) { "version must be positive" }
		require(createdAtEpochMillis >= 0) { "createdAtEpochMillis must not be negative" }
		require(expiresAtEpochMillis >= createdAtEpochMillis) {
			"expiresAtEpochMillis must not be before createdAtEpochMillis"
		}
	}
}

data class HopAcknowledgement(
	val packetId: PacketId,
	val nodeId: NodeId,
	val accepted: Boolean,
	val status: Int = if (accepted) 0 else 1,
	val finalDelivery: Boolean = false,
) : Serializable
