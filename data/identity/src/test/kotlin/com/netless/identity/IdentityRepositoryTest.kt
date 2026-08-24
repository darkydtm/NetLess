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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

		assertFailsWith<SecurityException> { repository.getOrCreateIdentity() }
		assertEquals(null, store.profile)

		assertFailsWith<SecurityException> {
			repository.updateProfile(UpdateProfileCommand(name = "Ada"))
		}
		assertEquals(null, store.profile)
		Unit
	}

	@Test
	fun rejectsTamperedPersistedProfilesBeforeExposingThem() = runBlocking {
		val crypto = FakeCryptoProvider()
		val store = FakeIdentityStore()
		val identity = StoredIdentity(
			ProfileId(crypto.sha256("identity-1".encodeToByteArray()).hex),
			PublicKey("identity-1".encodeToByteArray()),
			PrivateKeyRef("identity-1"),
		)
		store.identity = identity
		store.profile = Profile(
			id = identity.profileId,
			publicKey = identity.publicKey,
			name = "Tampered",
			bio = "",
			version = 0,
			signature = Signature(byteArrayOf(1)),
		)
		val repository = KeystoreIdentityRepository(crypto, store)

		assertFailsWith<SecurityException> { repository.getOrCreateIdentity() }
		Unit
	}

	@Test
	fun repairsPersistedIdentityIdFromItsPublicKey() = runBlocking {
		val crypto = FakeCryptoProvider()
		val store = FakeIdentityStore()
		store.identity = StoredIdentity(
			ProfileId("wrong"),
			PublicKey("identity-1".encodeToByteArray()),
			PrivateKeyRef("identity-1"),
		)
		val repository = KeystoreIdentityRepository(crypto, store)

		val identity = repository.getOrCreateIdentity()

		assertEquals(ProfileId(crypto.sha256(identity.publicKey.encoded).hex), identity.profileId)
		assertEquals(identity.profileId, store.identity?.profileId)
	}

	@Test
	fun rejectsMissingPersistedIdentityAlias() = runBlocking {
		val crypto = FakeCryptoProvider(existingAliases = emptySet())
		val store = FakeIdentityStore()
		store.identity = StoredIdentity(
			ProfileId(crypto.sha256("identity-1".encodeToByteArray()).hex),
			PublicKey("identity-1".encodeToByteArray()),
			PrivateKeyRef("missing"),
		)
		val repository = KeystoreIdentityRepository(crypto, store)

		assertFailsWith<IllegalStateException> { repository.getOrCreateIdentity() }
		Unit
	}

	@Test
	fun serializesConcurrentProfileUpdates() = runBlocking {
		val store = FakeIdentityStore()
		val repository = KeystoreIdentityRepository(FakeCryptoProvider(), store)
		repository.getOrCreateIdentity()

		val profiles = coroutineScope {
			(1..20).map { index -> async { repository.updateProfile(UpdateProfileCommand("Name $index")) } }.awaitAll()
		}

		assertEquals((1L..20L).toSet(), profiles.map { it.version }.toSet())
		assertEquals(20, profiles.maxOf { it.version })
		assertEquals(20, store.profile?.version)
		assertEquals(profiles.single { it.version == 20L }, store.profile)
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
	private val existingAliases: Set<String> = setOf("identity-1", "identity-2"),
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

	override fun hasPrivateKey(privateKey: PrivateKeyRef): Boolean = privateKey.alias in existingAliases

	override fun sha256(data: ByteArray): Hash = Hash(MessageDigest.getInstance("SHA-256").digest(data))
}
