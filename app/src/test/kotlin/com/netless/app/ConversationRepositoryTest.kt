package com.netless.app

import com.netless.content.ContentCipher
import com.netless.content.DurableEncryptedContentStore
import com.netless.content.ConversationContentCipher
import com.netless.content.ConversationKeyRegistry
import javax.crypto.KeyGenerator
import com.netless.protocol.DeliveryState
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import com.netless.common.ProfileId
import com.netless.common.NodeId
import com.netless.transport.TransportEndpoint
import java.util.Base64
import java.security.KeyPairGenerator

class ConversationRepositoryTest {
	@Test
	fun `messages remain discoverable after repository recreation`() = runTest {
		val file = File.createTempFile("conversation", ".db").also { it.delete() }
		val store = DurableEncryptedContentStore(file, PlainCipher)
		val first = repository(store)
        first.addContact(validProfileId(), "Alex", "node", "endpoint", encodedPublicKey())
		first.send("conversation", "hello", SendPolicy.Automatic).first()

		val restored = repository(DurableEncryptedContentStore(file, PlainCipher))
		assertEquals(listOf("hello"), restored.observeMessages("conversation").first().map { it.body })
		file.delete()
	}

	@Test
	fun `contact identity does not depend on endpoint`() = runTest {
		val repository = repository(DurableEncryptedContentStore(File.createTempFile("conversation", ".db"), PlainCipher))
        val profileId = validProfileId()
        repository.addContact(profileId, "Alex", "node", "endpoint", encodedPublicKey())
        assertEquals(profileId, repository.contacts().single().profileId)
	}

	@Test
	fun `contact requires complete identity route`() {
		val repository = repository(DurableEncryptedContentStore(File.createTempFile("conversation", ".db"), PlainCipher))
		kotlin.test.assertFailsWith<IllegalArgumentException> { repository.addContact("profile", "Alex", "", "endpoint", "bad") }
	}

	@Test
	fun `contact rejects malformed identity key`() {
		val repository = repository(DurableEncryptedContentStore(File.createTempFile("conversation", ".db"), PlainCipher))
		kotlin.test.assertFailsWith<IllegalArgumentException> { repository.addContact("profile", "Alex", "node", "endpoint", "%%%") }
	}

	@Test
	fun `send exposes delivery state`() = runTest {
		val repository = repository(DurableEncryptedContentStore(File.createTempFile("conversation", ".db"), PlainCipher))
		assertEquals(DeliveryState.Queued, repository.send("conversation", "hello", SendPolicy.Automatic).first())
	}
}

private val testPublicKey = Base64.getEncoder().encodeToString(KeyPairGenerator.getInstance("EC").generateKeyPair().public.encoded)
private fun encodedPublicKey(): String = testPublicKey
private fun validProfileId(): String = com.netless.common.ProfileId(java.security.MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(testPublicKey)).joinToString("") { "%02x".format(it) }).value

private object PlainCipher : ContentCipher {
	override fun encrypt(content: ByteArray) = content
		override fun decrypt(content: ByteArray) = content
}

private class FakeSender : MessageSender {
	override suspend fun send(message: ChatMessage, payload: com.netless.content.ConversationMessagePayload, policy: SendPolicy) = DeliveryState.Delivered
}

private fun repository(store: DurableEncryptedContentStore): ConversationRepository {
	val keys = ConversationKeyRegistry({ _, _, _ -> true }, store).also { it.register("conversation", KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()) }
    return ConversationRepository(store, FakeSender(), ConversationContentCipher(keys), ContactStore(store) { _, _, _ -> true }, "local") { it.copy(signature = com.netless.crypto.Signature(byteArrayOf(1))) }
}
