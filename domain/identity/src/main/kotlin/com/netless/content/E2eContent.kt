package com.netless.content

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class EncryptedContent(
	val keyId: String,
	val ciphertext: ByteArray,
	val authenticationTag: ByteArray,
)

class E2eContentCipher(secret: ByteArray) {
	private val encryptionKey = SecretKeySpec(sha256(secret, "encryption"), "AES")
	private val authenticationKey = SecretKeySpec(sha256(secret, "authentication"), "HmacSHA256")
	private val cipher = AesContentCipher(encryptionKey)

	constructor(key: javax.crypto.SecretKey) : this(key.encoded)

	fun encrypt(keyId: String, associatedData: ByteArray, plaintext: ByteArray): EncryptedContent {
		require(keyId.isNotBlank()) { "keyId must not be blank" }
		val ciphertext = cipher.encrypt(plaintext)
		return EncryptedContent(keyId, ciphertext, hmac(keyId, associatedData, ciphertext))
	}

	fun decrypt(content: EncryptedContent, associatedData: ByteArray): ByteArray {
		val expected = hmac(content.keyId, associatedData, content.ciphertext)
		require(MessageDigest.isEqual(expected, content.authenticationTag)) { "content authentication failed" }
		return cipher.decrypt(content.ciphertext)
	}

	private fun hmac(keyId: String, associatedData: ByteArray, ciphertext: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
		init(authenticationKey)
		update(keyId.encodeToByteArray())
		update(associatedData)
		doFinal(ciphertext)
	}

	private fun sha256(secret: ByteArray, purpose: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(secret + purpose.encodeToByteArray())
}

fun messageAssociatedData(id: String, conversationId: String): ByteArray = ByteArrayOutputStream().also {
		DataOutputStream(it).use { output ->
			output.writeUTF(id)
			output.writeUTF(conversationId)
		}
	}.toByteArray()
