package com.netless.identity

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.netless.common.ProfileId
import com.netless.crypto.CryptoProvider
import com.netless.crypto.Hash
import com.netless.crypto.IdentityKeyPair
import com.netless.crypto.PrivateKeyRef
import com.netless.crypto.PublicKey
import com.netless.crypto.Signature
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature as JavaSignature
import java.security.spec.X509EncodedKeySpec
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull

class KeystoreIdentityRepository internal constructor(
	private val crypto: CryptoProvider,
	private val store: IdentityStore,
) : IdentityRepository {
	private val profileState = MutableStateFlow(store.profile)

	constructor(context: Context) : this(AndroidKeystoreCryptoProvider(), AndroidIdentityStore(context))

	override suspend fun getOrCreateIdentity(): DeviceIdentity {
		val identity = store.identity ?: crypto.generateIdentity().let { keyPair ->
			StoredIdentity(
				profileId = ProfileId(crypto.sha256(keyPair.publicKey.encoded).hex),
				publicKey = keyPair.publicKey,
				privateKey = keyPair.privateKey,
			).also { store.identity = it }
		}
		if (store.profile == null) {
			store.profile = createSignedProfile(identity, "New device", "", 0)
			profileState.value = store.profile
		}
		return DeviceIdentity(identity.profileId, identity.publicKey)
	}

	override fun observeProfile(): Flow<Profile> = profileState.filterNotNull()

	override suspend fun updateProfile(command: UpdateProfileCommand): Profile {
		getOrCreateIdentity()
		val identity = store.identity ?: error("identity was not created")
		val current = store.profile ?: error("profile was not created")
		val profile = createSignedProfile(identity, command.name, command.bio, current.version + 1)
		if (!crypto.verify(profile.publicKey, profile.signedPayload(), profile.signature)) {
			throw SecurityException("Profile signature verification failed")
		}
		store.profile = profile
		profileState.value = profile
		return profile
	}

	private suspend fun createSignedProfile(
		identity: StoredIdentity,
		name: String,
		bio: String,
		version: Long,
	): Profile {
		val payload = Profile.payload(identity.profileId, identity.publicKey, name, bio, version)
		return Profile(
			id = identity.profileId,
			publicKey = identity.publicKey,
			name = name,
			bio = bio,
			version = version,
			signature = crypto.sign(identity.privateKey, payload),
		)
	}
}

private class AndroidKeystoreCryptoProvider : CryptoProvider {
	override suspend fun generateIdentity(): IdentityKeyPair {
		val alias = "netless-identity-${UUID.randomUUID()}"
		val generator = java.security.KeyPairGenerator.getInstance(
			KeyProperties.KEY_ALGORITHM_EC,
			"AndroidKeyStore",
		)
		generator.initialize(
			KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
				.setDigests(KeyProperties.DIGEST_SHA256)
				.build(),
		)
		val pair = generator.generateKeyPair()
		return IdentityKeyPair(PublicKey(pair.public.encoded), PrivateKeyRef(alias))
	}

	override suspend fun sign(privateKey: PrivateKeyRef, data: ByteArray): Signature {
		val key = keyStore().getKey(privateKey.alias, null) as? PrivateKey
			?: throw IllegalStateException("Missing identity key ${privateKey.alias}")
		return JavaSignature.getInstance("SHA256withECDSA").run {
			initSign(key)
			update(data)
			Signature(sign())
		}
	}

	override suspend fun verify(publicKey: PublicKey, data: ByteArray, signature: Signature): Boolean =
		try {
			JavaSignature.getInstance("SHA256withECDSA").run {
				initVerify(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKey.encoded)))
				update(data)
				verify(signature.bytes)
			}
		} catch (_: GeneralSecurityException) {
			false
		}

	override fun sha256(data: ByteArray): Hash =
		Hash(java.security.MessageDigest.getInstance("SHA-256").digest(data))

	private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
}

internal data class StoredIdentity(
	val profileId: ProfileId,
	val publicKey: PublicKey,
	val privateKey: PrivateKeyRef,
)

internal interface IdentityStore {
	var identity: StoredIdentity?
	var profile: Profile?
}

internal class AndroidIdentityStore(context: Context) : IdentityStore {
	private val preferences = context.applicationContext.getSharedPreferences("netless-identity", Context.MODE_PRIVATE)

	override var identity: StoredIdentity?
		get() = preferences.getString("private-key-alias", null)?.let { alias ->
			val publicKey = preferences.getString("public-key", null) ?: return null
			val profileId = preferences.getString("profile-id", null) ?: return null
			StoredIdentity(
				profileId = ProfileId(profileId),
				publicKey = PublicKey(Base64.decode(publicKey, Base64.NO_WRAP)),
				privateKey = PrivateKeyRef(alias),
			)
		}
		set(value) {
			preferences.edit().apply {
				if (value == null) {
					clear()
				} else {
					putString("private-key-alias", value.privateKey.alias)
					putString("public-key", Base64.encodeToString(value.publicKey.encoded, Base64.NO_WRAP))
					putString("profile-id", value.profileId.value)
				}
			}.apply()
		}

	override var profile: Profile?
		get() {
			val profileId = preferences.getString("profile-id", null) ?: return null
			val publicKey = preferences.getString("profile-public-key", null) ?: return null
			val name = preferences.getString("profile-name", null) ?: return null
			val signature = preferences.getString("profile-signature", null) ?: return null
			return Profile(
				id = ProfileId(profileId),
				publicKey = PublicKey(Base64.decode(publicKey, Base64.NO_WRAP)),
				name = name,
				bio = preferences.getString("profile-bio", "") ?: "",
				version = preferences.getLong("profile-version", 0),
				signature = Signature(Base64.decode(signature, Base64.NO_WRAP)),
			)
		}
		set(value) {
			preferences.edit().apply {
				if (value == null) {
					remove("profile-public-key")
					remove("profile-name")
					remove("profile-bio")
					remove("profile-version")
					remove("profile-signature")
				} else {
					putString("profile-public-key", Base64.encodeToString(value.publicKey.encoded, Base64.NO_WRAP))
					putString("profile-name", value.name)
					putString("profile-bio", value.bio)
					putLong("profile-version", value.version)
					putString("profile-signature", Base64.encodeToString(value.signature.bytes, Base64.NO_WRAP))
				}
			}.apply()
		}
}
