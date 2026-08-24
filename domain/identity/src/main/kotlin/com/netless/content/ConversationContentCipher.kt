package com.netless.content

import com.netless.crypto.EphemeralKeyExchange
import com.netless.crypto.KeyExchangeOffer

class ConversationContentCipher(
	private val keys: ConversationKeyRegistry,
) {
	fun establish(
		offer: KeyExchangeOffer,
		localEphemeral: EphemeralKeyExchange,
		remoteEphemeralPublicKey: ByteArray,
	) {
		keys.establish(offer, localEphemeral, remoteEphemeralPublicKey)
	}

	fun encrypt(sessionId: String, messageId: String, conversationId: String, plaintext: ByteArray): EncryptedContent =
		E2eContentCipher(keys.key(sessionId)).encrypt(sessionId, messageAssociatedData(messageId, conversationId), plaintext)

	fun decrypt(sessionId: String, messageId: String, conversationId: String, content: EncryptedContent): ByteArray =
		E2eContentCipher(keys.key(sessionId)).decrypt(content, messageAssociatedData(messageId, conversationId))
}
