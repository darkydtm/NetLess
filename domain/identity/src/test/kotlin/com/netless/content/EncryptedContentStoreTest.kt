package com.netless.content

import kotlin.test.Test
import kotlin.test.assertContentEquals

class EncryptedContentStoreTest {
	@Test
	fun roundTripsEncryptedContent() {
		val store = EncryptedContentStore(AesContentCipher())
		store.put("message", "secret".encodeToByteArray())

		assertContentEquals("secret".encodeToByteArray(), store.get("message"))
	}
}
