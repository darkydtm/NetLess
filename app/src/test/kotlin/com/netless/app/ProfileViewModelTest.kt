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
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

	@Test
	fun rapidSavesPersistOnlyOnce() = runTest {
		val repository = FakeIdentityRepository()
		val viewModel = ProfileViewModel(repository)
		viewModel.nameChanged("Ada")

		viewModel.save()
		viewModel.save()
		advanceUntilIdle()

		assertEquals(1, repository.updateCount)
	}

	@Test
	fun saveRethrowsCancellation() = runTest {
		val repository = FakeIdentityRepository(updateError = CancellationException("cancelled"))
		val viewModel = ProfileViewModel(repository)
		viewModel.nameChanged("Ada")

		viewModel.save()

		assertFailsWith<CancellationException> { advanceUntilIdle() }
	}

	@Test
	fun initializationFailureStopsLoadingAndExposesError() = runTest {
		val repository = FakeIdentityRepository(identityError = IllegalStateException("identity unavailable"))
		val viewModel = ProfileViewModel(repository)
		advanceUntilIdle()

		assertFalse(viewModel.uiState.value.loading)
		assertTrue(viewModel.uiState.value.error!!.contains("identity unavailable"))
	}

	private class FakeIdentityRepository(
		private val identityError: Throwable? = null,
		private val updateError: Throwable? = null,
	) : IdentityRepository {
		private val profile = MutableStateFlow(profile("New device", ""))
		var lastCommand: UpdateProfileCommand? = null
		var updateCount = 0

		override suspend fun getOrCreateIdentity() = identityError?.let { throw it }
			?: DeviceIdentity(profile.value.id, profile.value.publicKey)
		override fun observeProfile(): Flow<Profile> = profile
		override suspend fun updateProfile(command: UpdateProfileCommand): Profile {
			updateCount++
			updateError?.let { throw it }
			lastCommand = command
			return profile(command.name, command.bio).also { profile.value = it }
		}

		private fun profile(name: String, bio: String) = Profile(
			id = ProfileId("profile"), publicKey = PublicKey(byteArrayOf(1)), name = name,
			bio = bio, version = if (name == "New device") 0 else 1, signature = Signature(byteArrayOf(1)),
		)
	}
}
