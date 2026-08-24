package com.netless.content

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class E2eContentTest {
	@Test
	fun authenticatesAssociatedDataAndPayload() {
		val cipher = E2eContentCipher("shared-secret".encodeToByteArray())
		val data = messageAssociatedData("message", "conversation")
		val encrypted = cipher.encrypt("key-1", data, "hello".encodeToByteArray())

		assertContentEquals("hello".encodeToByteArray(), cipher.decrypt(encrypted, data))
		assertFailsWith<IllegalArgumentException> { cipher.decrypt(encrypted, messageAssociatedData("other", "conversation")) }
	}
}
