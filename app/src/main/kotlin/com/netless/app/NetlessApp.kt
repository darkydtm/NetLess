package com.netless.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.netless.transport.DiscoveredNode

@Composable
fun NetlessApp(viewModel: ProfileViewModel, container: AppContainer) {
	val contacts by container.contacts.contacts.collectAsState()
	val messengerViewModel = remember { MessengerViewModel() }
	LaunchedEffect(container) {
		container.runtimeController.startDiscovery()
	}
	MessengerShell(messengerViewModel, viewModel, contacts)
}

@Composable
private fun ProfileScreen(
	state: ProfileUiState,
	contacts: List<DiscoveredNode>,
	messages: List<com.netless.content.Message>,
	message: String,
	onMessageChanged: (String) -> Unit,
	onSendMessage: () -> Unit,
	onNameChanged: (String) -> Unit,
	onBioChanged: (String) -> Unit,
	onSave: () -> Unit,
) {
	val haptics = LocalHapticFeedback.current
	Column(
		modifier = Modifier.fillMaxSize().padding(24.dp).animateContentSize(),
		verticalArrangement = Arrangement.spacedBy(16.dp),
	) {
		Text(
			text = if (state.profile?.version == 0L) "Create your profile" else "Your profile",
			style = MaterialTheme.typography.headlineMedium,
		)
		OutlinedTextField(
			value = state.name,
			onValueChange = onNameChanged,
			label = { Text("Name") },
			modifier = Modifier.fillMaxWidth(),
			singleLine = true,
			isError = state.error != null && state.name.isBlank(),
		)
		OutlinedTextField(
			value = state.bio,
			onValueChange = onBioChanged,
			label = { Text("Bio") },
			modifier = Modifier.fillMaxWidth(),
			minLines = 3,
		)
		state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
		AnimatedContent(targetState = contacts.size, label = "contact-count") { count -> Text("Nearby contacts: $count") }
		contacts.forEach { contact -> Text(contact.endpoint.address, style = MaterialTheme.typography.bodyMedium) }
		Text("Messages", style = MaterialTheme.typography.titleMedium)
		messages.forEach { item ->
			AnimatedVisibility(visible = true, enter = slideInVertically { it / 2 } + fadeIn(), exit = slideOutVertically() + fadeOut()) { Text(item.body) }
		}
		OutlinedTextField(value = message, onValueChange = onMessageChanged, label = { Text("Message") }, modifier = Modifier.fillMaxWidth())
		Button(onClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onSendMessage() }, enabled = message.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Send") }
		Button(onClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onSave() }, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) {
			Text(if (state.saving) "Saving..." else "Save profile")
		}
	}
}
