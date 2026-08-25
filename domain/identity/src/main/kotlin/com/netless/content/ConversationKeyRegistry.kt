package com.netless.content

import com.netless.crypto.AuthenticatedKeyExchange
import com.netless.crypto.EphemeralKeyExchange
import com.netless.crypto.KeyExchangeOffer
import com.netless.crypto.PublicKey
import javax.crypto.SecretKey

class ConversationKeyRegistry(
	private val verifyIdentity: (PublicKey, ByteArray, com.netless.crypto.Signature) -> Boolean,
	private val store: DurableEncryptedContentStore? = null,
) {
	private val keys = HashMap<String, SecretKey>()
	private val sessions = HashSet<String>()
	init { store?.ids()?.filter { it.startsWith("conversation-key:") }?.forEach { id -> store.get(id)?.let { sessionId -> keys[id.removePrefix("conversation-key:")] = javax.crypto.spec.SecretKeySpec(sessionId, "AES"); sessions.add(id.removePrefix("conversation-key:")) } } }

	fun register(sessionId: String, key: SecretKey) {
		require(sessionId.isNotBlank()) { "sessionId must not be blank" }
		keys[sessionId] = key
		sessions.add(sessionId)
		store?.put("conversation-key:$sessionId", key.encoded)
	}

	fun establish(
		offer: KeyExchangeOffer,
		local: EphemeralKeyExchange,
		remotePublicKey: ByteArray,
	): SecretKey {
		val exchange = AuthenticatedKeyExchange(verifyIdentity)
		require(exchange.verifyOffer(offer)) { "key exchange identity signature failed" }
		require(sessions.add(offer.sessionId)) { "key exchange session already used" }
		val transcript = EphemeralKeyExchange.transcript(local.publicKey, remotePublicKey, offer.sessionId)
		return local.derive(remotePublicKey, transcript).also { key ->
			keys[offer.sessionId] = key
			store?.put("conversation-key:${offer.sessionId}", key.encoded)
		}
	}

	fun key(sessionId: String): SecretKey = keys[sessionId]
		?: error("conversation key is not registered")
}
