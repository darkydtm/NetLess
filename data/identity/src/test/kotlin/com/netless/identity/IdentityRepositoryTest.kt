package com.netless.identity

import com.netless.common.ProfileId
import com.netless.crypto.CryptoProvider
import com.netless.crypto.Hash
import com.netless.crypto.IdentityKeyPair
import com.netless.crypto.PrivateKeyRef
import com.netless.crypto.PublicKey
import com.netless.crypto.Signature
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdentityRepositoryTest {
	@Test
	fun createsOneStableIdentity() = runBlocking {
		val crypto = FakeCryptoProvider()
		val repository = KeystoreIdentityRepository(crypto, FakeIdentityStore())

		val first = repository.getOrCreateIdentity()
		val second = repository.getOrCreateIdentity()

		assertEquals(first, second)
		assertEquals(ProfileId(crypto.sha256(first.publicKey.encoded).hex), first.profileId)
		assertEquals(1, crypto.generatedIdentities)
	}

	@Test
	fun signsProfileUpdates() = runBlocking {
		val crypto = FakeCryptoProvider()
		val repository = KeystoreIdentityRepository(crypto, FakeIdentityStore())

		repository.getOrCreateIdentity()
		val profile = repository.updateProfile(UpdateProfileCommand(name = "Ada", bio = "Offline"))

		assertEquals(1, profile.version)
		assertTrue(crypto.verify(profile.publicKey, profile.signedPayload(), profile.signature))
	}

	@Test
	fun rejectsInvalidProfileSignaturesWithoutSavingThem() = runBlocking {
		val store = FakeIdentityStore()
		val repository = KeystoreIdentityRepository(FakeCryptoProvider(acceptSignatures = false), store)

		repository.getOrCreateIdentity()

		assertFailsWith<SecurityException> {
			repository.updateProfile(UpdateProfileCommand(name = "Ada"))
		}
		assertEquals(0, store.profile?.version)
	}

	@Test
	fun exposesNoPrivateIdentityKey() {
		assertFalse(DeviceIdentity::class.java.declaredFields.any { it.name.contains("private", ignoreCase = true) })
		assertFalse(IdentityRepository::class.java.methods.any { it.name.contains("export", ignoreCase = true) })
	}
}

private class FakeIdentityStore : IdentityStore {
	override var identity: StoredIdentity? = null
	override var profile: Profile? = null
}

private class FakeCryptoProvider(
	private val acceptSignatures: Boolean = true,
) : CryptoProvider {
	var generatedIdentities = 0

	override suspend fun generateIdentity(): IdentityKeyPair {
		generatedIdentities += 1
		return IdentityKeyPair(
			PublicKey("identity-$generatedIdentities".encodeToByteArray()),
			PrivateKeyRef("identity-$generatedIdentities"),
		)
	}

	override suspend fun sign(privateKey: PrivateKeyRef, data: ByteArray): Signature =
		Signature(MessageDigest.getInstance("SHA-256").digest(data))

	override suspend fun verify(publicKey: PublicKey, data: ByteArray, signature: Signature): Boolean =
		acceptSignatures && signature.bytes.contentEquals(
			MessageDigest.getInstance("SHA-256").digest(data),
		)

	override fun sha256(data: ByteArray): Hash = Hash(MessageDigest.getInstance("SHA-256").digest(data))
}
