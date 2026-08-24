package com.netless.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.netless.identity.IdentityRepository
import com.netless.identity.Profile
import com.netless.identity.UpdateProfileCommand
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfileUiState(
	val loading: Boolean = true,
	val profile: Profile? = null,
	val name: String = "",
	val bio: String = "",
	val saving: Boolean = false,
	val error: String? = null,
)

class ProfileViewModel(private val repository: IdentityRepository) : ViewModel() {
	private val _uiState = MutableStateFlow(ProfileUiState())
	val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

	init {
		viewModelScope.launch {
			repository.getOrCreateIdentity()
			repository.observeProfile().collect { profile ->
				_uiState.update { it.copy(loading = false, profile = profile, name = profile.name, bio = profile.bio) }
			}
		}
	}

	fun nameChanged(name: String) = _uiState.update { it.copy(name = name, error = null) }

	fun bioChanged(bio: String) = _uiState.update { it.copy(bio = bio, error = null) }

	fun save() {
		val state = uiState.value
		if (state.name.isBlank() || state.saving) {
			_uiState.update { it.copy(error = "Name is required") }
			return
		}
		viewModelScope.launch {
			_uiState.update { it.copy(saving = true, error = null) }
			runCatching { repository.updateProfile(UpdateProfileCommand(state.name.trim(), state.bio.trim())) }
				.onFailure { error -> _uiState.update { it.copy(saving = false, error = error.message ?: "Could not save profile") } }
				.onSuccess { _uiState.update { it.copy(saving = false) } }
		}
	}

	companion object {
		fun factory(repository: IdentityRepository): ViewModelProvider.Factory =
			object : ViewModelProvider.Factory {
				@Suppress("UNCHECKED_CAST")
				override fun <T : ViewModel> create(modelClass: Class<T>): T = ProfileViewModel(repository) as T
			}
	}
}
