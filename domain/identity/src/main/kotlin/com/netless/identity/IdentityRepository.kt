package com.netless.identity

import com.netless.common.ProfileId
import com.netless.crypto.PublicKey
import com.netless.crypto.Signature
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlinx.coroutines.flow.Flow

interface IdentityRepository {
	suspend fun getOrCreateIdentity(): DeviceIdentity
	fun observeProfile(): Flow<Profile>
	suspend fun updateProfile(command: UpdateProfileCommand): Profile
}

data class DeviceIdentity(
	val profileId: ProfileId,
	val publicKey: PublicKey,
)

data class UpdateProfileCommand(
	val name: String,
	val bio: String = "",
) {
	init {
		require(name.isNotBlank()) { "name must not be blank" }
	}
}

data class Profile(
	val id: ProfileId,
	val publicKey: PublicKey,
	val name: String,
	val bio: String,
	val version: Long,
	val signature: Signature,
) {
	init {
		require(name.isNotBlank()) { "name must not be blank" }
		require(version >= 0) { "version must not be negative" }
		require(signature.bytes.isNotEmpty()) { "signature must not be empty" }
	}

	fun signedPayload(): ByteArray = payload(id, publicKey, name, bio, version)

	companion object {
		fun payload(id: ProfileId, publicKey: PublicKey, name: String, bio: String, version: Long): ByteArray {
			val bytes = ByteArrayOutputStream()
			val output = DataOutputStream(bytes)
			writeField(output, id.value.encodeToByteArray())
			writeField(output, publicKey.encoded)
			writeField(output, name.encodeToByteArray())
			writeField(output, bio.encodeToByteArray())
			output.writeLong(version)
			return bytes.toByteArray()
		}

		private fun writeField(output: DataOutputStream, value: ByteArray) {
			output.writeInt(value.size)
			output.write(value)
		}
	}
}
