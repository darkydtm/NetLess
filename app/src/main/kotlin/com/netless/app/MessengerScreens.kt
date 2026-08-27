package com.netless.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.theme.*

@Composable
fun MessengerApp(viewModel: MessengerViewModel, profile: ProfileViewModel, contacts: List<com.netless.transport.DiscoveredNode>) {
	val state by viewModel.uiState.collectAsState()
	val profileState by profile.uiState.collectAsState()
	var tab by remember { mutableStateOf(MessengerTab.Chats) }
	var contactDraft by remember { mutableStateOf("") }
	val controller = remember { ThemeController(ColorSchemeMode.System) }
	MiuixTheme(controller) {
		Scaffold(topBar = { TopAppBar(title = state.selectedConversation?.let { id -> state.conversations.firstOrNull { it.id == id }?.title } ?: tab.name) }, bottomBar = {
			if (state.selectedConversation == null) NavigationBar {
				MessengerTab.entries.forEach { item -> NavigationBarItem(selected = tab == item, onClick = { tab = item; viewModel.selectTab(item) }, icon = NavigationPlaceholder, label = item.name) }
			}
		}) { padding ->
			Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
				when {
					state.selectedConversation != null -> ConversationScreen(state, viewModel)
					tab == MessengerTab.Chats -> ChatListScreen(state, viewModel)
					tab == MessengerTab.Contacts -> ContactsScreen(contacts, contactDraft, { contactDraft = it }, viewModel)
					else -> SettingsScreen(profile, state, viewModel)
				}
			}
		}
	}
}

private val NavigationPlaceholder = ImageVector.Builder("navigation", 24.dp, 24.dp, 24f, 24f).build()

@Composable private fun ChatListScreen(state: MessengerUiState, viewModel: MessengerViewModel) {
		Text("Private messages over the mesh")
		LazyColumn { items(state.conversations) { conversation ->
			Card(Modifier.fillMaxWidth().clickable { viewModel.selectConversation(conversation.id) }.semantics { contentDescription = "Open ${conversation.title} conversation" }) { Column(Modifier.padding(16.dp)) { Text(conversation.title); Text(conversation.preview) } }
		} }
}

@Composable private fun ConversationScreen(state: MessengerUiState, viewModel: MessengerViewModel) {
		LazyColumn { items(state.messages) { message -> Column(Modifier.padding(vertical = 6.dp)) { Text(message.body); Text(state.deliveryByMessageId[message.id] ?: message.deliveryState.name) } } }
		Row { BasicTextField(state.draft, viewModel::draftChanged, Modifier.padding(12.dp), decorationBox = { inner -> if (state.draft.isEmpty()) Text("Message") else inner() }); Button(onClick = viewModel::send, enabled = state.draft.isNotBlank()) { Text("Send") } }
}

@Composable private fun ContactsScreen(contacts: List<com.netless.transport.DiscoveredNode>, draft: String, onDraft: (String) -> Unit, viewModel: MessengerViewModel) {
	BasicTextField(draft, onDraft, Modifier.fillMaxWidth().padding(12.dp), decorationBox = { inner -> if (draft.isEmpty()) Text("profileId | displayName | nodeId | endpoint | identityKey") else inner() })
	Button(onClick = { viewModel.addContactText(draft); onDraft("") }, enabled = draft.contains('|')) { Text("Add contact") }
	if (contacts.isEmpty()) Text("No nearby contacts yet") else contacts.forEach { Text(it.endpoint.address) }
}

@Composable private fun SettingsScreen(profile: ProfileViewModel, state: MessengerUiState, viewModel: MessengerViewModel) {
	val profileState by profile.uiState.collectAsState()
	Text("Network", style = MiuixTheme.textStyles.title1)
	Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { viewModel.setPolicy(com.netless.transport.TransportPolicy.Automatic()) }) { Text("Automatic") }; Button(onClick = { viewModel.setPolicy(com.netless.transport.TransportPolicy.Strict(com.netless.transport.TransportType.Bluetooth)) }) { Text("Bluetooth only") } }
	Text("Profile", style = MiuixTheme.textStyles.title1)
	BasicTextField(profileState.name, profile::nameChanged, Modifier.fillMaxWidth().padding(12.dp))
	BasicTextField(profileState.bio, profile::bioChanged, Modifier.fillMaxWidth().padding(12.dp))
	Button(onClick = profile::save, enabled = !profileState.saving) { Text("Save profile") }
	state.deliveryLabel?.let { Text(it) }
}
