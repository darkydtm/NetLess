package com.netless.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.activity.compose.PredictiveBackHandler

@Composable
fun NetlessApp(viewModel: ProfileViewModel, container: AppContainer) {
	val messenger = remember(container) { MessengerViewModel(container.conversations) { container.routeDetails() } }
	val profile by viewModel.uiState.collectAsStateWithLifecycle()
	val state by messenger.uiState.collectAsStateWithLifecycle()
	LaunchedEffect(container) { container.runtimeController.startDiscovery() }
	PredictiveBackHandler(enabled = state.selectedConversation != null) { progress ->
		progress.collect { }
		messenger.closeConversation()
	}
	PrototypeApp(profile, state, messenger, viewModel)
}
