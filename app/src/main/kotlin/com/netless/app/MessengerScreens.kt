package com.netless.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.netless.transport.TransportPolicy
import com.netless.transport.TransportType

@Composable
fun MessengerShell(viewModel: MessengerViewModel, profile: ProfileViewModel, contacts: List<com.netless.transport.DiscoveredNode>) {
	val state by viewModel.uiState.collectAsState()
	Row(Modifier.fillMaxSize()) {
		NavigationBar(Modifier.widthIn(max = 96.dp)) {
			MessengerTab.entries.forEach { tab ->
				NavigationBarItem(selected = state.currentTab == tab, onClick = { viewModel.selectTab(tab) }, icon = { Text(tab.name.first().toString()) }, label = { Text(tab.name) })
			}
		}
		Box(Modifier.weight(1f).fillMaxHeight().padding(16.dp)) {
			AnimatedContent(state.currentTab, label = "messenger-tab") { tab -> when (tab) {
				MessengerTab.Chats -> ChatListScreen(state, viewModel)
				MessengerTab.Contacts -> ContactsScreen(contacts)
				MessengerTab.Settings -> SettingsScreen(profile, state, viewModel)
			} }
		}
	}
}

@Composable fun ChatListScreen(state: MessengerUiState, viewModel: MessengerViewModel) {
	Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
		Text("Netless", style = MaterialTheme.typography.headlineMedium)
		Text("Private messages, even without the internet", style = MaterialTheme.typography.bodyMedium)
		LazyColumn { items(state.conversations) { conversation -> ListItem(headlineContent = { Text(conversation.title) }, supportingContent = { Text(conversation.preview) }, modifier = Modifier.fillMaxWidth().clickable { viewModel.selectConversation(conversation.id) }.semantics { contentDescription = "Open ${conversation.title} conversation" }, leadingContent = { Text("N", style = MaterialTheme.typography.titleLarge) }); Spacer(Modifier.height(4.dp)) } }
		ConversationScreen(state, viewModel)
	}
}

@Composable fun ConversationScreen(state: MessengerUiState, viewModel: MessengerViewModel) {
	Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
		Text(state.conversations.firstOrNull { it.id == state.selectedConversation }?.title ?: "Conversation", style = MaterialTheme.typography.titleLarge)
		state.deliveryLabel?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(state.draft, viewModel::draftChanged, Modifier.weight(1f), label = { Text("Message") }); Button({ viewModel.send() }, enabled = state.draft.isNotBlank(), modifier = Modifier.semantics { contentDescription = "Send message" }) { Text("Send") } }
	}
}

@Composable fun ContactsScreen(contacts: List<com.netless.transport.DiscoveredNode>) { Column { Text("Contacts", style = MaterialTheme.typography.headlineMedium); if (contacts.isEmpty()) Text("No nearby contacts yet"); contacts.forEach { Text(it.endpoint.address, Modifier.padding(vertical = 8.dp)) } } }

@Composable fun SettingsScreen(profile: ProfileViewModel, state: MessengerUiState, viewModel: MessengerViewModel) { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { Text("Settings", style = MaterialTheme.typography.headlineMedium); Text("Network", style = MaterialTheme.typography.titleMedium); NetworkSettingsScreen(state, viewModel); Text("Profile", style = MaterialTheme.typography.titleMedium); ProfileFields(profile) } }

@Composable fun ProfileFields(viewModel: ProfileViewModel) { val state by viewModel.uiState.collectAsState(); OutlinedTextField(state.name, viewModel::nameChanged, label = { Text("Name") }); OutlinedTextField(state.bio, viewModel::bioChanged, label = { Text("Bio") }); Button(viewModel::save) { Text("Save profile") } }

@Composable fun NetworkSettingsScreen(state: MessengerUiState, viewModel: MessengerViewModel) { Text("Automatic routing keeps the technical details out of your chats."); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button({ viewModel.setPolicy(TransportPolicy.Automatic()) }) { Text("Automatic") }; Button({ viewModel.setPolicy(TransportPolicy.Strict(TransportType.Bluetooth)) }) { Text("Strict") } }; if (state.strictWarningVisible) AlertDialog(onDismissRequest = {}, title = { Text("Strict routing warning") }, text = { Text("Messages will use Bluetooth only and may not arrive.") }, confirmButton = { TextButton(viewModel::confirmStrictMode) { Text("Use strict mode") } }, dismissButton = { TextButton({ viewModel.setPolicy(TransportPolicy.Automatic()) }) { Text("Cancel") } }); TextButton(viewModel::toggleExpertRoute, modifier = Modifier.semantics { contentDescription = "Show route details" }) { Text(if (state.routeDetails == null) "Show route details" else "Hide route details") }; state.routeDetails?.let { RouteDetailsSheet(it) } }

@Composable fun RouteDetailsSheet(hops: List<String>) { Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text("Expert route", style = MaterialTheme.typography.titleMedium); hops.forEachIndexed { index, hop -> Text("${index + 1}. $hop") } } } }
