package com.netless.crypto

interface CryptoProvider {
	suspend fun generateIdentity(): IdentityKeyPair
	suspend fun sign(privateKey: PrivateKeyRef, data: ByteArray): Signature
	suspend fun verify(publicKey: PublicKey, data: ByteArray, signature: Signature): Boolean
	fun sha256(data: ByteArray): Hash
}

class PublicKey(encoded: ByteArray) {
	private val encodedValue = encoded.copyOf()

	init {
		require(encodedValue.isNotEmpty()) { "encoded public key must not be empty" }
	}

	val encoded: ByteArray
		get() = encodedValue.copyOf()

	override fun equals(other: Any?): Boolean = other is PublicKey && encodedValue.contentEquals(other.encodedValue)

	override fun hashCode(): Int = encodedValue.contentHashCode()
}

@JvmInline
value class PrivateKeyRef(val alias: String) {
	init {
		require(alias.isNotBlank()) { "alias must not be blank" }
	}
}

class Signature(bytes: ByteArray) {
	private val bytesValue = bytes.copyOf()

	init {
		require(bytesValue.isNotEmpty()) { "signature must not be empty" }
	}

	val bytes: ByteArray
		get() = bytesValue.copyOf()

	override fun equals(other: Any?): Boolean = other is Signature && bytesValue.contentEquals(other.bytesValue)

	override fun hashCode(): Int = bytesValue.contentHashCode()
}

class Hash(bytes: ByteArray) {
	private val bytesValue = bytes.copyOf()

	init {
		require(bytesValue.isNotEmpty()) { "hash must not be empty" }
	}

	val bytes: ByteArray
		get() = bytesValue.copyOf()
	val hex: String
		get() = bytesValue.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

	override fun equals(other: Any?): Boolean = other is Hash && bytesValue.contentEquals(other.bytesValue)

	override fun hashCode(): Int = bytesValue.contentHashCode()
}

data class IdentityKeyPair(
	val publicKey: PublicKey,
	val privateKey: PrivateKeyRef,
)
