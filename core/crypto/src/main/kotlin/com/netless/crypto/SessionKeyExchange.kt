package com.netless.crypto

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey as JcaPublicKey
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

class EphemeralKeyExchange private constructor(private val keyPair: KeyPair) {
	val publicKey: ByteArray = keyPair.public.encoded

	fun derive(peerPublicKey: ByteArray, transcript: ByteArray): SecretKey {
		val peer = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(peerPublicKey))
		val agreement = KeyAgreement.getInstance("ECDH")
		agreement.init(keyPair.private)
		agreement.doPhase(peer, true)
		val shared = agreement.generateSecret()
		val key = MessageDigest.getInstance("SHA-256").digest(shared + transcript)
		return SecretKeySpec(key, "AES")
	}

	companion object {
		fun generate(): EphemeralKeyExchange = EphemeralKeyExchange(
			KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair(),
		)

		fun transcript(first: ByteArray, second: ByteArray, sessionId: String): ByteArray {
			val ordered = if (compare(first, second) <= 0) listOf(first, second) else listOf(second, first)
			return ordered[0] + ordered[1] + sessionId.encodeToByteArray()
		}

		private fun compare(first: ByteArray, second: ByteArray): Int {
			for (index in 0 until minOf(first.size, second.size)) {
				val result = (first[index].toInt() and 0xff) - (second[index].toInt() and 0xff)
				if (result != 0) return result
			}
			return first.size - second.size
		}
	}
}
