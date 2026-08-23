package com.netless.protocol

import com.netless.common.NodeId
import java.io.Serializable
import java.util.Collections

sealed interface ProtocolEvent : Serializable

class HandshakeHello(
	val protocolVersion: Int,
	val nodeId: NodeId,
	nonce: ByteArray,
) : ProtocolEvent {
	private val nonceValue = nonce.copyOf()
	val nonce: ByteArray
		get() = nonceValue.copyOf()

	init {
		require(protocolVersion > 0) { "protocolVersion must be positive" }
		require(nonceValue.isNotEmpty()) { "nonce must not be empty" }
	}

	override fun equals(other: Any?): Boolean =
		other is HandshakeHello &&
			protocolVersion == other.protocolVersion &&
			nodeId == other.nodeId &&
			nonceValue.contentEquals(other.nonceValue)

	override fun hashCode(): Int {
		var result = protocolVersion
		result = 31 * result + nodeId.hashCode()
		result = 31 * result + nonceValue.contentHashCode()
		return result
	}
}

class HandshakeChallenge(
	challenge: ByteArray,
	val expiresAtEpochSeconds: Long,
) : ProtocolEvent {
	private val challengeValue = challenge.copyOf()
	val challenge: ByteArray
		get() = challengeValue.copyOf()

	init {
		require(challengeValue.isNotEmpty()) { "challenge must not be empty" }
		require(expiresAtEpochSeconds > 0) { "expiresAtEpochSeconds must be positive" }
	}

	override fun equals(other: Any?): Boolean =
		other is HandshakeChallenge &&
			expiresAtEpochSeconds == other.expiresAtEpochSeconds &&
			challengeValue.contentEquals(other.challengeValue)

	override fun hashCode(): Int = 31 * challengeValue.contentHashCode() + expiresAtEpochSeconds.hashCode()
}

class HandshakeResponse(
	challenge: ByteArray,
	signature: ByteArray,
) : ProtocolEvent {
	private val challengeValue = challenge.copyOf()
	private val signatureValue = signature.copyOf()
	val challenge: ByteArray
		get() = challengeValue.copyOf()
	val signature: ByteArray
		get() = signatureValue.copyOf()

	init {
		require(challengeValue.isNotEmpty()) { "challenge must not be empty" }
		require(signatureValue.isNotEmpty()) { "signature must not be empty" }
	}

	override fun equals(other: Any?): Boolean =
		other is HandshakeResponse &&
			challengeValue.contentEquals(other.challengeValue) &&
			signatureValue.contentEquals(other.signatureValue)

	override fun hashCode(): Int = 31 * challengeValue.contentHashCode() + signatureValue.contentHashCode()
}

class CapabilityExchange(capabilities: Set<String>) : ProtocolEvent {
	val capabilities: Set<String> = Collections.unmodifiableSet(capabilities.toMutableSet())

	init {
		require(this.capabilities.isNotEmpty()) { "capabilities must not be empty" }
		require(this.capabilities.none { it.isBlank() }) { "capabilities must not contain blank values" }
	}

	override fun equals(other: Any?): Boolean = other is CapabilityExchange && capabilities == other.capabilities

	override fun hashCode(): Int = capabilities.hashCode()
}

data class SessionEstablished(
	val sessionId: String,
	val protocolVersion: Int,
) : ProtocolEvent {
	init {
		require(sessionId.isNotBlank()) { "sessionId must not be blank" }
		require(protocolVersion > 0) { "protocolVersion must be positive" }
	}
}
