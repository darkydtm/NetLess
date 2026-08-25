package com.netless.app

import com.netless.content.ContentCipher
import com.netless.content.DurableEncryptedContentStore
import com.netless.protocol.DeliveryState
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class ConversationRepositoryTest {
	@Test
	fun `messages remain discoverable after repository recreation`() = runTest {
		val file = File.createTempFile("conversation", ".db").also { it.delete() }
		val store = DurableEncryptedContentStore(file, PlainCipher)
		val first = ConversationRepository(store, FakeSender())
		first.addContact("profile", "Alex")
		first.send("conversation", "hello", SendPolicy.Automatic).first()

		val restored = ConversationRepository(DurableEncryptedContentStore(file, PlainCipher), FakeSender())
		assertEquals(listOf("hello"), restored.observeMessages("conversation").first().map { it.body })
		file.delete()
	}

	@Test
	fun `contact identity does not depend on endpoint`() = runTest {
		val repository = ConversationRepository(DurableEncryptedContentStore(File.createTempFile("conversation", ".db"), PlainCipher), FakeSender())
		repository.addContact("profile", "Alex")
		repository.updateEndpoint("profile", "endpoint")
		assertEquals("profile", repository.contacts().single().profileId)
	}

	@Test
	fun `send exposes delivery state`() = runTest {
		val repository = ConversationRepository(DurableEncryptedContentStore(File.createTempFile("conversation", ".db"), PlainCipher), FakeSender())
		assertEquals(DeliveryState.Queued, repository.send("conversation", "hello", SendPolicy.Automatic).first())
	}
}

private object PlainCipher : ContentCipher {
	override fun encrypt(content: ByteArray) = content
		override fun decrypt(content: ByteArray) = content
}

private class FakeSender : MessageSender {
	override suspend fun send(conversationId: String, text: String, policy: SendPolicy) = DeliveryState.Delivered
}
