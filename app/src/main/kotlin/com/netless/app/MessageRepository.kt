package com.netless.app

import com.netless.content.DurableEncryptedContentStore
import com.netless.content.Message
import com.netless.protocol.ContentEnvelope
import java.nio.charset.StandardCharsets
import java.util.UUID

class MessageRepository(private val store: DurableEncryptedContentStore) {
	private val ids = LinkedHashMap<String, String>()

	@Synchronized
	fun send(conversationId: String, body: String): Message {
		require(conversationId.isNotBlank() && body.isNotBlank())
		val message = Message(UUID.randomUUID().toString(), conversationId, body)
		store.put(message.id, encode(message))
		ids[message.id] = conversationId
		return message
	}

	@Synchronized
	fun messages(conversationId: String): List<Message> = ids.filterValues { it == conversationId }.keys.mapNotNull { id ->
		store.get(id)?.let(::decode)
	}

	@Synchronized
	suspend fun onContent(content: ContentEnvelope) {
		store.put("pending:${content.eventId}", content.encryptedPayload)
	}

	private fun encode(message: Message): ByteArray = listOf(message.id, message.conversationId, message.body).joinToString("\u0000").toByteArray(StandardCharsets.UTF_8)

	private fun decode(bytes: ByteArray): Message {
		val parts = String(bytes, StandardCharsets.UTF_8).split('\u0000', limit = 3)
		require(parts.size == 3) { "invalid stored message" }
		return Message(parts[0], parts[1], parts[2])
	}
}
