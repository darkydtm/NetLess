package com.netless.app

import com.netless.content.DurableEncryptedContentStore
import com.netless.content.ConversationContentCipher
import com.netless.content.ConversationMessagePayload
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
import kotlinx.coroutines.CancellationException
import java.util.Base64
import com.netless.crypto.PublicKey

internal object IdentityKeyCodec {
	fun canonicalize(value: String): String? = runCatching {
		val key = PublicKey(Base64.getDecoder().decode(value))
		Base64.getEncoder().encodeToString(key.encoded)
	}.getOrNull()
}

data class Contact(val profileId: String, val displayName: String, val nodeId: String, val endpoint: String, val identityKey: String)
data class ChatMessage(val id: String, val conversationId: String, val body: String, val timestamp: Long, val deliveryState: DeliveryState, val read: Boolean = true)
data class ConversationSummary(val conversationId: String, val contactProfileId: String, val lastMessagePreview: String, val timestamp: Long, val unreadCount: Int, val deliveryState: DeliveryState)
sealed interface SendPolicy {
	data object Automatic : SendPolicy
	data class Network(val policy: com.netless.transport.TransportPolicy) : SendPolicy
}
interface MessageSender {
	suspend fun send(message: ChatMessage, payload: ConversationMessagePayload, policy: SendPolicy): DeliveryState
}

class ConversationRepository(private val store: DurableEncryptedContentStore, private val sender: MessageSender, private val contentCipher: ConversationContentCipher, private val contactStore: ContactStore, private val localProfileId: String) {
	init { require(localProfileId.isNotBlank()) }
	private val messages = LinkedHashMap<String, ChatMessage>()
	private val state = MutableStateFlow<List<ChatMessage>>(emptyList())
	private val conversationState = MutableStateFlow<List<ConversationSummary>>(emptyList())
	private val deliveryState = HashMap<String, MutableStateFlow<DeliveryState>>()
	private val lock = Any()
	init {
		store.ids().filter { it.startsWith("conversation-message:") || it.startsWith("message:") }.forEach { key -> runCatching { store.get(key)?.let(::decode) }.getOrNull()?.also { messages[it.id] = it } }
		publish()
	}
	fun observeConversations(): Flow<List<ConversationSummary>> = conversationState.asStateFlow()
	fun observeMessages(conversationId: String): Flow<List<ChatMessage>> = state.map { it.filter { message -> message.conversationId == conversationId } }
	fun messages(conversationId: String) = state.value.filter { it.conversationId == conversationId }
	fun observeDelivery(messageId: String): Flow<DeliveryState> = deliveryState.getOrPut(messageId) { MutableStateFlow(state.value.firstOrNull { it.id == messageId }?.deliveryState ?: DeliveryState.Failed) }.asStateFlow()
	fun contacts() = contactStore.contacts.value.mapNotNull { node -> node.endpoint.metadata["profileId"]?.let { profile -> Contact(profile, node.endpoint.metadata["displayName"] ?: profile, node.nodeId.value, node.endpoint.address, node.endpoint.metadata["identityKey"].orEmpty()) } }
	fun addContact(profileId: String, displayName: String, nodeId: String, endpoint: String, identityKey: String) = synchronized(lock) {
		require(listOf(profileId, displayName, nodeId, endpoint, identityKey).all { it.isNotBlank() }) { "profileId, displayName, nodeId, endpoint, and identityKey are required" }
		val canonicalIdentityKey = IdentityKeyCodec.canonicalize(identityKey)
		require(canonicalIdentityKey != null) { "identityKey must be a valid Base64-encoded public key" }
		val contact = Contact(profileId, displayName, nodeId, endpoint, canonicalIdentityKey!!)
		contactStore.upsert(com.netless.common.ProfileId(profileId), displayName, com.netless.common.NodeId(nodeId), com.netless.transport.TransportEndpoint(com.netless.common.NodeId(nodeId), endpoint, emptyMap()), canonicalIdentityKey!!)
		publish()
	}
	fun markRead(conversationId: String) = synchronized(lock) { messages.values.filter { it.conversationId == conversationId && !it.read }.forEach { save(it.copy(read = true)) } }
	suspend fun onIncomingContent(content: ContentEnvelope) {
		val payload = runCatching { ConversationMessagePayload.decode(content.encryptedPayload) }.getOrNull() ?: return
		require(contactStore.contact(content.senderProfileId.value) != null && payload.conversationId == content.senderProfileId.value) { "sender is not authorized for conversation" }
		require(content.recipients.any { it.value == localProfileId }) { "content is not addressed to local profile" }
		require(payload.messageId == content.eventId) { "content message id does not match event id" }
		require(messages[payload.messageId] == null) { "duplicate message id" }
			val body = contentCipher.decrypt(payload.sessionId, payload.messageId, payload.conversationId, payload.content).decodeToString()
			val conversationId = payload.conversationId
			val messageId = payload.messageId
			addIncoming(ChatMessage(messageId, conversationId, body, System.currentTimeMillis(), DeliveryState.Delivered, false))
	}
	fun send(conversationId: String, text: String, policy: SendPolicy): Flow<DeliveryState> = kotlinx.coroutines.flow.flow {
		require(conversationId.isNotBlank() && text.isNotBlank()); val message = ChatMessage(UUID.randomUUID().toString(), conversationId, text, System.currentTimeMillis(), DeliveryState.Queued); val payload = ConversationMessagePayload(conversationId, message.id, conversationId, contentCipher.encrypt(conversationId, message.id, conversationId, text.encodeToByteArray())); save(message); emitState(message.id, DeliveryState.Queued); emit(DeliveryState.Queued); val result = try { sender.send(message, payload, policy) } catch (error: CancellationException) { throw error } catch (_: Exception) { DeliveryState.Failed }; save(message.copy(deliveryState = result)); emitState(message.id, result); emit(result)
	}
	fun addIncoming(message: ChatMessage) = save(message.copy(read = false))
	private fun save(message: ChatMessage) = synchronized(lock) { messages[message.id] = message; deliveryState.getOrPut(message.id) { MutableStateFlow(message.deliveryState) }.value = message.deliveryState; store.put("conversation-message:${message.id}", encode(message)); publish() }
	private fun emitState(id: String, state: DeliveryState) { deliveryState.getOrPut(id) { MutableStateFlow(state) }.value = state }
	private fun publish() { state.value = messages.values.toList(); conversationState.value = messages.values.groupBy { it.conversationId }.map { (id, values) -> values.maxBy { it.timestamp }.let { ConversationSummary(id, id, it.body, it.timestamp, values.count { !it.read }, it.deliveryState) } } }
	private fun encode(m: ChatMessage) = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeUTF(m.id); writeUTF(m.conversationId); writeUTF(m.body); writeLong(m.timestamp); writeUTF(m.deliveryState.name); writeBoolean(m.read) } }.toByteArray()
	private fun decode(b: ByteArray): ChatMessage = DataInputStream(ByteArrayInputStream(b)).use { input -> ChatMessage(input.readUTF(), input.readUTF(), input.readUTF(), input.readLong(), input.readUTF().let(DeliveryState::valueOf), input.readBoolean()).also { require(input.available() == 0) } }
	private fun encode(c: Contact) = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeUTF(c.profileId); writeUTF(c.displayName); writeUTF(c.nodeId); writeUTF(c.endpoint); writeUTF(c.identityKey) } }.toByteArray()
	private fun decodeContact(b: ByteArray): Contact { val input = DataInputStream(ByteArrayInputStream(b)); return Contact(input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF(), input.readUTF()).also { require(input.available() == 0) } }
}
