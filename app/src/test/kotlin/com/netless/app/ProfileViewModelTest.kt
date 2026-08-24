package com.netless.app

import com.netless.common.ProfileId
import com.netless.crypto.PublicKey
import com.netless.crypto.Signature
import com.netless.identity.DeviceIdentity
import com.netless.identity.IdentityRepository
import com.netless.identity.Profile
import com.netless.identity.UpdateProfileCommand
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ProfileViewModelTest {
	@Test
	fun saveTrimsAndPersistsEditedProfile() = runTest {
		val repository = FakeIdentityRepository()
		val viewModel = ProfileViewModel(repository)
		viewModel.nameChanged(" Ada ")
		viewModel.bioChanged(" Offline ")
		viewModel.save()
		advanceUntilIdle()

		assertEquals(UpdateProfileCommand("Ada", "Offline"), repository.lastCommand)
		assertEquals("Ada", viewModel.uiState.value.profile?.name)
	}

	private class FakeIdentityRepository : IdentityRepository {
		private val profile = MutableStateFlow(profile("New device", ""))
		var lastCommand: UpdateProfileCommand? = null

		override suspend fun getOrCreateIdentity() = DeviceIdentity(profile.value.id, profile.value.publicKey)
		override fun observeProfile(): Flow<Profile> = profile
		override suspend fun updateProfile(command: UpdateProfileCommand): Profile {
			lastCommand = command
			return profile(command.name, command.bio).also { profile.value = it }
		}

		private fun profile(name: String, bio: String) = Profile(
			id = ProfileId("profile"), publicKey = PublicKey(byteArrayOf(1)), name = name,
			bio = bio, version = if (name == "New device") 0 else 1, signature = Signature(byteArrayOf(1)),
		)
	}
}
