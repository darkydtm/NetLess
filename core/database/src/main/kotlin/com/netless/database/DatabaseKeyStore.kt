package com.netless.database

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class WrappedDatabaseKey(
	val keyId: String,
	wrappedKey: ByteArray,
) {
	private val wrappedKeyValue = wrappedKey.copyOf()

	val wrappedKey: ByteArray
		get() = wrappedKeyValue.copyOf()

	init {
		require(keyId.isNotBlank()) { "keyId must not be blank" }
		require(wrappedKeyValue.isNotEmpty()) { "wrappedKey must not be empty" }
	}

	override fun equals(other: Any?): Boolean =
		other is WrappedDatabaseKey && keyId == other.keyId && wrappedKeyValue.contentEquals(other.wrappedKeyValue)

	override fun hashCode(): Int = 31 * keyId.hashCode() + wrappedKeyValue.contentHashCode()
}

interface KeyWrapper {
	fun wrap(key: ByteArray): ByteArray
	fun unwrap(wrappedKey: ByteArray): ByteArray
}

class DatabaseKeyStore(private val keyWrapper: KeyWrapper = AndroidKeystoreKeyWrapper()) {
	fun createWrappedKey(): WrappedDatabaseKey {
		val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
		return WrappedDatabaseKey(UUID.randomUUID().toString(), keyWrapper.wrap(key))
	}

	fun unwrap(key: WrappedDatabaseKey): ByteArray = keyWrapper.unwrap(key.wrappedKey)
}

class AndroidKeystoreKeyWrapper(private val alias: String = "netless-database-key-wrapper") : KeyWrapper {
	override fun wrap(key: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
		val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
		init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, iv))
		iv + doFinal(key)
	}

	override fun unwrap(wrappedKey: ByteArray): ByteArray {
		require(wrappedKey.size > 12) { "wrappedKey is too short" }
		return Cipher.getInstance("AES/GCM/NoPadding").run {
			init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, wrappedKey.copyOfRange(0, 12)))
			doFinal(wrappedKey.copyOfRange(12, wrappedKey.size))
		}
	}

	private fun key(): SecretKey = (KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
		.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(
		KeyProperties.KEY_ALGORITHM_AES,
		"AndroidKeyStore",
	).apply {
		init(
			KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
				.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
				.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
				.build(),
		)
	}.generateKey()
}
