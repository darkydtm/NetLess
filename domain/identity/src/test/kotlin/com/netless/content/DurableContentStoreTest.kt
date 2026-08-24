package com.netless.content

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals

class DurableContentStoreTest {
	@Test
	fun restoresEncryptedRecordsAfterReopen() {
		val file = Files.createTempFile("netless-content", ".db").toFile()
		val cipher = AesContentCipher()
		DurableEncryptedContentStore(file, cipher).put("id", "persisted".encodeToByteArray())

		assertContentEquals("persisted".encodeToByteArray(), DurableEncryptedContentStore(file, cipher).get("id"))
		file.delete()
	}
}
