package com.netless.transport

import com.netless.crypto.EphemeralKeyExchange
import com.netless.crypto.KeyExchangeOffer
import com.netless.crypto.PublicKey
import com.netless.crypto.Signature
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class SessionTransport(private val socket: Socket) {
	private val input = DataInputStream(socket.getInputStream())
	private val output = DataOutputStream(socket.getOutputStream())
	private var negotiatedKey: SecretKey? = null
	private var authenticatedSessionId: String? = null

	suspend fun close() = socket.close()

	suspend fun establish(protocolVersion: Int, sessionId: String) {
		require(protocolVersion > 0 && sessionId.isNotBlank()) { "invalid session parameters" }
		synchronized(output) {
			output.writeInt(protocolVersion)
			output.writeUTF(sessionId)
			output.flush()
		}
	}

	suspend fun establishAuthenticated(
		protocolVersion: Int,
		sessionId: String,
		identityPublicKey: PublicKey,
		sign: suspend (ByteArray) -> Signature,
		verify: suspend (PublicKey, ByteArray, Signature) -> Boolean,
	): SecretKey {
		require(protocolVersion > 0 && sessionId.isNotBlank()) { "invalid session parameters" }
		val local = EphemeralKeyExchange.generate()
		val offer = KeyExchangeOffer(
			sessionId = sessionId,
			ephemeralPublicKey = local.publicKey,
			identityPublicKey = identityPublicKey,
			signature = sign(exchangePayload(sessionId, local.publicKey, identityPublicKey)),
		)
		writeOffer(protocolVersion, offer)
		val remote = readOffer(protocolVersion, sessionId)
		require(verify(remote.identityPublicKey, exchangePayload(remote.sessionId, remote.ephemeralPublicKey, remote.identityPublicKey), remote.signature)) {
			throw SecurityException("session identity signature failed")
		}
		return local.derive(remote.ephemeralPublicKey, EphemeralKeyExchange.transcript(local.publicKey, remote.ephemeralPublicKey, sessionId)).also {
			negotiatedKey = it
			authenticatedSessionId = sessionId
		}
	}

	val sessionKey: SecretKey
		get() = negotiatedKey ?: error("session is not authenticated")

	fun packets(): Flow<ByteArray> = flow {
		while (!socket.isClosed) {
			val size = input.readInt()
			require(size in 0..MAX_PACKET_SIZE) { "packet exceeds session limit" }
			emit(decrypt(input.readNBytes(size)))
		}
	}

	suspend fun send(packet: ByteArray) {
		require(packet.size <= MAX_PACKET_SIZE) { "packet exceeds session limit" }
		val encrypted = encrypt(packet)
		synchronized(output) {
			output.writeInt(encrypted.size)
			output.write(encrypted)
			output.flush()
		}
	}

	private fun encrypt(packet: ByteArray): ByteArray {
		val key = negotiatedKey ?: error("session is not authenticated")
		val iv = ByteArray(GCM_IV_SIZE).also(SecureRandom()::nextBytes)
		val cipher = Cipher.getInstance("AES/GCM/NoPadding")
		cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
		cipher.updateAAD(authenticatedSessionId!!.encodeToByteArray())
		return iv + cipher.doFinal(packet)
	}

	private fun decrypt(packet: ByteArray): ByteArray {
		require(packet.size > GCM_IV_SIZE + GCM_TAG_SIZE) { "encrypted packet is too short" }
		val key = negotiatedKey ?: error("session is not authenticated")
		val iv = packet.copyOfRange(0, GCM_IV_SIZE)
		val cipher = Cipher.getInstance("AES/GCM/NoPadding")
		cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
		cipher.updateAAD(authenticatedSessionId!!.encodeToByteArray())
		return cipher.doFinal(packet.copyOfRange(GCM_IV_SIZE, packet.size))
	}

	private fun writeOffer(protocolVersion: Int, offer: KeyExchangeOffer) {
		synchronized(output) {
			output.writeInt(protocolVersion)
			output.writeUTF(offer.sessionId)
			writeBytes(offer.ephemeralPublicKey)
			writeBytes(offer.identityPublicKey.encoded)
			writeBytes(offer.signature.bytes)
			output.flush()
		}
	}

	private fun readOffer(protocolVersion: Int, sessionId: String): KeyExchangeOffer {
		require(input.readInt() == protocolVersion) { "session protocol mismatch" }
		require(input.readUTF() == sessionId) { "session id mismatch" }
		return KeyExchangeOffer(
			sessionId,
			readBytes(),
			PublicKey(readBytes()),
			Signature(readBytes()),
		)
	}

	private fun writeBytes(value: ByteArray) {
		output.writeInt(value.size)
		output.write(value)
	}

	private fun readBytes(): ByteArray {
		val size = input.readInt()
		require(size in 1..MAX_HANDSHAKE_FIELD_SIZE) { "invalid handshake field" }
		return input.readNBytes(size).also { require(it.size == size) { "truncated handshake" } }
	}

	private fun exchangePayload(sessionId: String, ephemeral: ByteArray, identity: PublicKey): ByteArray =
		AuthenticatedKeyExchangePayload.create(sessionId, ephemeral, identity)

	private companion object {
		const val MAX_PACKET_SIZE = 4 * 1024 * 1024
		const val MAX_HANDSHAKE_FIELD_SIZE = 16 * 1024
		const val GCM_IV_SIZE = 12
		const val GCM_TAG_SIZE = 16
	}
}

private object AuthenticatedKeyExchangePayload {
	fun create(sessionId: String, ephemeral: ByteArray, identity: PublicKey): ByteArray =
		java.security.MessageDigest.getInstance("SHA-256").digest(sessionId.encodeToByteArray() + ephemeral + identity.encoded)
}
