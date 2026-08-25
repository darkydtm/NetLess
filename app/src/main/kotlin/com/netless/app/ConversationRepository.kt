package com.netless.app

import com.netless.content.DurableEncryptedContentStore
import com.netless.protocol.ContentEnvelope
import com.netless.protocol.DeliveryState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

data class Contact(val profileId: String, val displayName: String, val endpoint: String? = null)
data class ChatMessage(val id: String, val conversationId: String, val body: String, val timestamp: Long, val deliveryState: DeliveryState, val read: Boolean = true)
data class ConversationSummary(val conversationId: String, val contactProfileId: String, val lastMessagePreview: String, val timestamp: Long, val unreadCount: Int, val deliveryState: DeliveryState)
sealed interface SendPolicy {
	data object Automatic : SendPolicy
	data class Network(val policy: com.netless.transport.TransportPolicy) : SendPolicy
}
interface MessageSender { suspend fun send(conversationId: String, text: String, policy: SendPolicy): DeliveryState }

class ConversationRepository(private val store: DurableEncryptedContentStore, private val sender: MessageSender, private val onContent: suspend (ContentEnvelope) -> Unit = {}) {
	private val messages = LinkedHashMap<String, ChatMessage>()
	private val contacts = LinkedHashMap<String, Contact>()
	private val state = MutableStateFlow<List<ChatMessage>>(emptyList())
	private val conversationState = MutableStateFlow<List<ConversationSummary>>(emptyList())
	private val lock = Any()
	init {
		store.ids().filter { it.startsWith("conversation-message:") || it.startsWith("message:") }.forEach { key -> runCatching { store.get(key)?.let(::decode) }.getOrNull()?.also { messages[it.id] = it } }
		store.ids().filter { it.startsWith("contact:") }.forEach { key -> runCatching { store.get(key)?.let(::decodeContact) }.getOrNull()?.also { contacts[it.profileId] = it } }
		publish()
	}
	fun observeConversations(): Flow<List<ConversationSummary>> = conversationState.asStateFlow()
	fun observeMessages(conversationId: String): Flow<List<ChatMessage>> = state.map { it.filter { message -> message.conversationId == conversationId } }
	fun messages(conversationId: String) = state.value.filter { it.conversationId == conversationId }
	fun contacts() = contacts.values.toList()
	fun addContact(profileId: String, displayName: String) = synchronized(lock) { require(profileId.isNotBlank() && displayName.isNotBlank()); val contact = Contact(profileId, displayName); contacts[profileId] = contact; store.put("contact:$profileId", encode(contact)); publish() }
	fun updateEndpoint(profileId: String, endpoint: String) = synchronized(lock) { require(endpoint.isNotBlank()); val contact = contacts[profileId] ?: error("unknown contact"); contacts[profileId] = contact.copy(endpoint = endpoint); store.put("contact:$profileId", encode(contacts.getValue(profileId))); publish() }
	fun markRead(conversationId: String) = synchronized(lock) { messages.values.filter { it.conversationId == conversationId && !it.read }.forEach { save(it.copy(read = true)) } }
	suspend fun onIncomingContent(content: ContentEnvelope) {
		runCatching {
			val input = DataInputStream(ByteArrayInputStream(store.open(content.encryptedPayload)))
			val conversationId = input.readUTF()
			val messageId = input.readUTF()
			val body = input.readUTF()
			require(input.available() == 0)
			addIncoming(ChatMessage(messageId, conversationId, body, System.currentTimeMillis(), DeliveryState.Delivered, false))
		}.getOrElse { onContent(content) }
	}
	fun send(conversationId: String, text: String, policy: SendPolicy): Flow<DeliveryState> = kotlinx.coroutines.flow.flow {
		require(conversationId.isNotBlank() && text.isNotBlank()); val message = ChatMessage(UUID.randomUUID().toString(), conversationId, text, System.currentTimeMillis(), DeliveryState.Queued); save(message); emit(DeliveryState.Queued); val result = runCatching { sender.send(conversationId, text, policy) }.getOrDefault(DeliveryState.Failed); save(message.copy(deliveryState = result)); emit(result)
	}
	fun encodeContent(conversationId: String, messageId: String, text: String): ByteArray = ByteArrayOutputStream().also { output -> DataOutputStream(output).use { it.writeUTF(conversationId); it.writeUTF(messageId); it.writeUTF(text) } }.toByteArray()
	fun addIncoming(message: ChatMessage) = save(message.copy(read = false))
	private fun save(message: ChatMessage) = synchronized(lock) { messages[message.id] = message; store.put("conversation-message:${message.id}", encode(message)); publish() }
	private fun publish() { state.value = messages.values.toList(); conversationState.value = messages.values.groupBy { it.conversationId }.map { (id, values) -> values.maxBy { it.timestamp }.let { ConversationSummary(id, contacts[id]?.profileId ?: id, it.body, it.timestamp, values.count { !it.read }, it.deliveryState) } } }
	private fun encode(m: ChatMessage) = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeUTF(m.id); writeUTF(m.conversationId); writeUTF(m.body); writeLong(m.timestamp); writeUTF(m.deliveryState.name); writeBoolean(m.read) } }.toByteArray()
	private fun decode(b: ByteArray): ChatMessage = runCatching {
		DataInputStream(ByteArrayInputStream(b)).use { input -> ChatMessage(input.readUTF(), input.readUTF(), input.readUTF(), input.readLong(), input.readUTF().let(DeliveryState::valueOf), input.readBoolean()) }
	}.getOrElse {
		val parts = String(b, Charsets.UTF_8).split('\u0000', limit = 3)
		require(parts.size == 3)
		ChatMessage(parts[0], parts[1], parts[2], 0L, DeliveryState.Delivered)
	}
	private fun encode(c: Contact) = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeUTF(c.profileId); writeUTF(c.displayName); writeBoolean(c.endpoint != null); if (c.endpoint != null) writeUTF(c.endpoint) } }.toByteArray()
	private fun decodeContact(b: ByteArray): Contact { val input = DataInputStream(ByteArrayInputStream(b)); return Contact(input.readUTF(), input.readUTF(), if (input.readBoolean()) input.readUTF() else null) }
}
