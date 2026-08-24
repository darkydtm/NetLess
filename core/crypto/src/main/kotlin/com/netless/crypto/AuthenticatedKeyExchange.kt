package com.netless.crypto

import java.security.MessageDigest

data class KeyExchangeOffer(
	val sessionId: String,
	val ephemeralPublicKey: ByteArray,
	val identityPublicKey: PublicKey,
	val signature: Signature,
)

fun interface ExchangeSigner {
	fun sign(data: ByteArray): Signature
}

class AuthenticatedKeyExchange(private val verify: (PublicKey, ByteArray, Signature) -> Boolean) {
	fun createOffer(sessionId: String, ephemeralPublicKey: ByteArray, identity: PublicKey, signer: ExchangeSigner): KeyExchangeOffer {
		val payload = payload(sessionId, ephemeralPublicKey, identity)
		return KeyExchangeOffer(sessionId, ephemeralPublicKey.copyOf(), identity, signer.sign(payload))
	}

	fun verifyOffer(offer: KeyExchangeOffer): Boolean = verify(offer.identityPublicKey, payload(offer.sessionId, offer.ephemeralPublicKey, offer.identityPublicKey), offer.signature)

	private fun payload(sessionId: String, ephemeral: ByteArray, identity: PublicKey): ByteArray = MessageDigest.getInstance("SHA-256").digest(
		(sessionId.encodeToByteArray() + ephemeral + identity.encoded),
	)
}
