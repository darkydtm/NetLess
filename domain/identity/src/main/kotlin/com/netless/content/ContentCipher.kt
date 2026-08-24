package com.netless.content

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface ContentCipher {
	fun encrypt(plainText: ByteArray): ByteArray
	fun decrypt(cipherText: ByteArray): ByteArray
}

class AesContentCipher(key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()) : ContentCipher {
	private val key = key

	override fun encrypt(plainText: ByteArray): ByteArray {
		val iv = ByteArray(12).also(SecureRandom()::nextBytes)
		val cipher = Cipher.getInstance("AES/GCM/NoPadding")
		cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
		return iv + cipher.doFinal(plainText)
	}

	override fun decrypt(cipherText: ByteArray): ByteArray {
		require(cipherText.size > 12) { "cipherText is too short" }
		val cipher = Cipher.getInstance("AES/GCM/NoPadding")
		cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, cipherText.copyOfRange(0, 12)))
		return cipher.doFinal(cipherText.copyOfRange(12, cipherText.size))
	}
}
