package com.netless.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.netless.identity.IdentityRepository
import com.netless.identity.Profile
import com.netless.identity.UpdateProfileCommand
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

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
	private val saveInProgress = AtomicBoolean()
	val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

	init {
		viewModelScope.launch {
			runCatching {
				repository.getOrCreateIdentity()
				repository.observeProfile().collect { profile ->
					_uiState.update { it.copy(loading = false, profile = profile, name = profile.name, bio = profile.bio) }
				}
			}.onFailure { error ->
				if (error is CancellationException) {
					throw error
				}
				_uiState.update { it.copy(loading = false, error = error.message ?: "Could not load profile") }
			}
		}
	}

	fun nameChanged(name: String) = _uiState.update { it.copy(name = name, error = null) }

	fun bioChanged(bio: String) = _uiState.update { it.copy(bio = bio, error = null) }

	fun save() {
		val state = uiState.value
		if (state.name.isBlank()) {
			_uiState.update { it.copy(error = "Name is required") }
			return
		}
		if (!saveInProgress.compareAndSet(false, true)) {
			return
		}
		_uiState.update { it.copy(saving = true, error = null) }
		viewModelScope.launch {
			try {
				repository.updateProfile(UpdateProfileCommand(state.name.trim(), state.bio.trim()))
			} catch (error: CancellationException) {
				throw error
			} catch (error: Throwable) {
				_uiState.update { it.copy(saving = false, error = error.message ?: "Could not save profile") }
			} finally {
				saveInProgress.set(false)
				_uiState.update { it.copy(saving = false) }
			}
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
