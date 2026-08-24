package com.netless.content

import com.netless.crypto.AuthenticatedKeyExchange
import com.netless.crypto.EphemeralKeyExchange
import com.netless.crypto.KeyExchangeOffer
import com.netless.crypto.PublicKey
import javax.crypto.SecretKey

class ConversationKeyRegistry(
	private val verifyIdentity: (PublicKey, ByteArray, com.netless.crypto.Signature) -> Boolean,
) {
	private val keys = HashMap<String, SecretKey>()
	private val sessions = HashSet<String>()

	fun establish(
		offer: KeyExchangeOffer,
		local: EphemeralKeyExchange,
		remotePublicKey: ByteArray,
	): SecretKey {
		val exchange = AuthenticatedKeyExchange(verifyIdentity)
		require(exchange.verifyOffer(offer)) { "key exchange identity signature failed" }
		require(sessions.add(offer.sessionId)) { "key exchange session already used" }
		val transcript = EphemeralKeyExchange.transcript(local.publicKey, remotePublicKey, offer.sessionId)
		return local.derive(remotePublicKey, transcript).also { keys[offer.sessionId] = it }
	}

	fun key(sessionId: String): SecretKey = keys[sessionId] ?: error("No key for session $sessionId")
}
