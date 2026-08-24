package com.netless.app

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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import com.netless.transport.DiscoveredNode

@Composable
fun NetlessApp(viewModel: ProfileViewModel, container: AppContainer) {
	val state by viewModel.uiState.collectAsStateWithLifecycle()
	val contacts by container.contacts.contacts.collectAsState()
	LaunchedEffect(container) {
		container.runtimeController.startDiscovery()
	}
	when {
		state.loading -> CircularProgressIndicator(Modifier.semantics { contentDescription = "Loading profile" })
		else -> ProfileScreen(state, contacts, viewModel::nameChanged, viewModel::bioChanged, viewModel::save)
	}
}

@Composable
private fun ProfileScreen(
	state: ProfileUiState,
	contacts: List<DiscoveredNode>,
	onNameChanged: (String) -> Unit,
	onBioChanged: (String) -> Unit,
	onSave: () -> Unit,
) {
	Column(
		modifier = Modifier.fillMaxSize().padding(24.dp),
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
		Text("Nearby contacts: ${contacts.size}")
		contacts.forEach { Text(it.endpoint.address, style = MaterialTheme.typography.bodyMedium) }
		Button(onClick = onSave, enabled = !state.saving, modifier = Modifier.fillMaxWidth()) {
			Text(if (state.saving) "Saving..." else "Save profile")
		}
	}
}
