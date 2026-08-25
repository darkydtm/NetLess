package com.netless.app

import com.netless.content.DurableEncryptedContentStore
import com.netless.protocol.ContentEnvelope
import com.netless.protocol.DeliveryState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.charset.StandardCharsets
import java.util.UUID

data class Contact(val profileId: String, val displayName: String, val endpoint: String? = null)
data class ChatMessage(val id: String, val conversationId: String, val body: String, val timestamp: Long, val deliveryState: DeliveryState)
data class ConversationSummary(val conversationId: String, val contactProfileId: String, val lastMessagePreview: String, val timestamp: Long, val unreadCount: Int, val deliveryState: DeliveryState)
sealed interface SendPolicy { data object Automatic : SendPolicy }
interface MessageSender { suspend fun send(conversationId: String, text: String, policy: SendPolicy): DeliveryState }

class ConversationRepository(private val store: DurableEncryptedContentStore, private val sender: MessageSender, private val onContent: suspend (ContentEnvelope) -> Unit = {}) {
	private val messages = LinkedHashMap<String, ChatMessage>()
	private val contacts = LinkedHashMap<String, Contact>()
	private val state = MutableStateFlow<List<ChatMessage>>(emptyList())
	init {
		store.ids().filter { it.startsWith("conversation-message:") }.forEach { store.get(it)?.let(::decode)?.also { message -> messages[message.id] = message } }
		store.ids().filter { it.startsWith("contact:") }.forEach { store.get(it)?.let(::decodeContact)?.also { contact -> contacts[contact.profileId] = contact } }
		state.value = messages.values.toList()
	}
	fun observeConversations(): Flow<List<ConversationSummary>> = kotlinx.coroutines.flow.flowOf(conversations())
	fun observeMessages(conversationId: String): Flow<List<ChatMessage>> = state.let { source -> kotlinx.coroutines.flow.flow { source.collect { emit(it.filter { message -> message.conversationId == conversationId }) } } }
	fun messages(conversationId: String) = state.value.filter { it.conversationId == conversationId }
	fun contacts() = contacts.values.toList()
	fun addContact(profileId: String, displayName: String) { val contact = Contact(profileId, displayName); contacts[profileId] = contact; store.put("contact:$profileId", encode(contact)) }
	fun updateEndpoint(profileId: String, endpoint: String) { val contact = contacts[profileId] ?: error("unknown contact"); contacts[profileId] = contact.copy(endpoint = endpoint); store.put("contact:$profileId", encode(contacts.getValue(profileId))) }
	fun send(conversationId: String, text: String, policy: SendPolicy): Flow<DeliveryState> = kotlinx.coroutines.flow.flow {
		val message = ChatMessage(UUID.randomUUID().toString(), conversationId, text, System.currentTimeMillis(), DeliveryState.Queued); save(message); emit(DeliveryState.Queued); val result = sender.send(conversationId, text, policy); save(message.copy(deliveryState = result)); emit(result)
	}
	private fun save(message: ChatMessage) { messages[message.id] = message; store.put("conversation-message:${message.id}", encode(message)); state.value = messages.values.toList() }
	private fun conversations() = messages.values.groupBy { it.conversationId }.map { (id, values) -> values.maxBy { it.timestamp }.let { ConversationSummary(id, contacts[id]?.profileId ?: id, it.body, it.timestamp, 0, it.deliveryState) } }
	private fun encode(m: ChatMessage) = listOf(m.id, m.conversationId, m.body, m.timestamp, m.deliveryState.name).joinToString("\u0000").toByteArray(StandardCharsets.UTF_8)
	private fun decode(b: ByteArray): ChatMessage { val p = String(b, StandardCharsets.UTF_8).split('\u0000', limit = 5); return ChatMessage(p[0], p[1], p[2], p[3].toLong(), DeliveryState.valueOf(p[4])) }
	private fun encode(c: Contact) = listOf(c.profileId, c.displayName, c.endpoint ?: "").joinToString("\u0000").toByteArray(StandardCharsets.UTF_8)
	private fun decodeContact(b: ByteArray): Contact { val p = String(b, StandardCharsets.UTF_8).split('\u0000', limit = 3); return Contact(p[0], p[1], p[2].ifEmpty { null }) }
}
