package com.netless.content

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

data class ConversationMessagePayload(
	val sessionId: String,
	val messageId: String,
	val conversationId: String,
	val content: EncryptedContent,
) {
	override fun equals(other: Any?): Boolean = other is ConversationMessagePayload && sessionId == other.sessionId && messageId == other.messageId && conversationId == other.conversationId && content.keyId == other.content.keyId && content.ciphertext.contentEquals(other.content.ciphertext) && content.authenticationTag.contentEquals(other.content.authenticationTag)
	override fun hashCode(): Int = (((31 * sessionId.hashCode() + messageId.hashCode()) * 31 + conversationId.hashCode()) * 31 + content.keyId.hashCode()) * 31 + content.ciphertext.contentHashCode() + content.authenticationTag.contentHashCode()
	fun encode(): ByteArray = ByteArrayOutputStream().also { bytes ->
		DataOutputStream(bytes).use { output ->
			output.writeInt(VERSION)
			output.writeUTF(sessionId)
			output.writeUTF(messageId)
			output.writeUTF(conversationId)
			output.writeUTF(content.keyId)
			output.writeInt(content.ciphertext.size)
			output.write(content.ciphertext)
			output.writeInt(content.authenticationTag.size)
			output.write(content.authenticationTag)
		}
	}.toByteArray()

	companion object {
		private const val VERSION = 1
		private const val MAX_FIELD_BYTES = 1 shl 20

		fun decode(bytes: ByteArray): ConversationMessagePayload = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
			require(input.readInt() == VERSION) { "unsupported conversation payload version" }
			val sessionId = input.readUTF()
			val messageId = input.readUTF()
			val conversationId = input.readUTF()
			val keyId = input.readUTF()
			val ciphertext = readBounded(input, "ciphertext")
			val tag = readBounded(input, "authentication tag")
			require(input.available() == 0)
			ConversationMessagePayload(sessionId, messageId, conversationId, EncryptedContent(keyId, ciphertext, tag))
		}

		private fun readBounded(input: DataInputStream, name: String): ByteArray {
			val size = input.readInt()
			require(size >= 0 && size <= MAX_FIELD_BYTES) { "$name size is out of bounds" }
			return ByteArray(size).also(input::readFully)
		}
	}
}
