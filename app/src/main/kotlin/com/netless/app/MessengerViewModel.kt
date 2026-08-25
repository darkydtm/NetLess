package com.netless.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.netless.protocol.DeliveryState
import com.netless.transport.TransportPolicy
import com.netless.transport.TransportType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class MessengerTab { Chats, Contacts, Settings }

data class ConversationUiState(val id: String, val title: String, val preview: String = "")

data class MessengerUiState(
	val currentTab: MessengerTab = MessengerTab.Chats,
	val conversations: List<ConversationUiState> = emptyList(),
	val selectedConversation: String? = null,
	val draft: String = "",
	val deliveryLabel: String? = null,
	val networkPolicy: TransportPolicy = TransportPolicy.Automatic(),
	val strictWarningVisible: Boolean = false,
	val routeDetails: List<String>? = null,
	val messages: List<ChatMessage> = emptyList(),
	val deliveryByMessageId: Map<String, String> = emptyMap(),
)

class MessengerViewModel(
	private val repository: ConversationRepository? = null,
	private val routeProvider: () -> List<String> = { emptyList() },
) : ViewModel() {
	private val _uiState = MutableStateFlow(MessengerUiState())
	val uiState: StateFlow<MessengerUiState> = _uiState.asStateFlow()

	init {
		repository?.let { repo ->
			viewModelScope.launch { repo.observeConversations().collect { summaries -> _uiState.update { state -> state.copy(conversations = summaries.map { ConversationUiState(it.conversationId, it.contactProfileId, it.lastMessagePreview) }, selectedConversation = state.selectedConversation ?: summaries.firstOrNull()?.conversationId) } } }
			viewModelScope.launch { _uiState.flatMapLatest { repo.observeMessages(it.selectedConversation ?: "") }.collect { messages -> _uiState.update { it.copy(messages = messages, deliveryByMessageId = messages.associate { message -> message.id to message.deliveryState.name }) } } }
		}
	}

	fun selectTab(tab: MessengerTab) = _uiState.update { it.copy(currentTab = tab) }

	fun selectConversation(id: String) { _uiState.update { it.copy(selectedConversation = id, currentTab = MessengerTab.Chats, messages = repository?.messages(id).orEmpty()) }; repository?.markRead(id) }

	fun draftChanged(text: String) = _uiState.update { it.copy(draft = text) }

	fun addContact(profileId: String, displayName: String) {
		val id = profileId.trim()
		val name = displayName.trim()
		repository?.addContact(id, name)
		_uiState.update { state -> state.copy(currentTab = MessengerTab.Chats, selectedConversation = id, conversations = (state.conversations + ConversationUiState(id, name)).distinctBy { it.id }) }
	}

	fun addContactText(value: String) {
		val parts = value.trim().split('|', limit = 2)
		if (parts.size == 2) addContact(parts[0], parts[1])
	}

	fun send(text: String = uiState.value.draft) {
		if (text.isBlank()) return
		val conversation = uiState.value.selectedConversation ?: return
		_uiState.update { state ->
			state.copy(
				draft = "",
				deliveryLabel = "Sending",
				conversations = state.conversations.map { item ->
					if (item.id == conversation) item.copy(preview = text.trim()) else item
				},
			)
		}
		repository?.let { repo -> viewModelScope.launch { repo.send(conversation, text.trim(), SendPolicy.Network(uiState.value.networkPolicy)).collect(::onDelivery) } }
	}

	fun setPolicy(policy: TransportPolicy) {
		if (policy.isStrict) _uiState.update { it.copy(strictWarningVisible = true) }
		else _uiState.update { it.copy(networkPolicy = policy, strictWarningVisible = false) }
	}

	fun confirmStrictMode() = _uiState.update { it.copy(networkPolicy = TransportPolicy.Strict(it.networkPolicy.strictTransport ?: TransportType.Bluetooth), strictWarningVisible = false) }

	fun dismissStrictWarning() = _uiState.update { it.copy(strictWarningVisible = false) }

	fun toggleExpertRoute() = _uiState.update { it.copy(routeDetails = if (it.routeDetails == null) routeProvider() else null) }

	fun onDelivery(state: DeliveryState) = _uiState.update { it.copy(deliveryLabel = when (state) {
		DeliveryState.Queued -> "Queued"
		DeliveryState.Relaying -> "Relayed"
		DeliveryState.Delivered -> "Delivered"
		DeliveryState.Expired -> "Expired"
		DeliveryState.Failed -> "Failed - try again"
	}) }
}
