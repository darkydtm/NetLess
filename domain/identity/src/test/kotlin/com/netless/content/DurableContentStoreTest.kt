package com.netless.content

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals

class DurableContentStoreTest {
	@Test
	fun putRollsBackMemoryWhenPersistenceFails() {
		val directory = java.io.File.createTempFile("content-store", ".db").also { it.delete(); it.mkdir() }
		val store = DurableEncryptedContentStore(java.io.File(directory, "store.db"), PlainCipher)
		assertFailsWith<Exception> { store.put("id", "value".encodeToByteArray()) }
		assertEquals(emptyList(), store.ids())
	}
	@Test
	fun replacementRollsBackToPreviousValueWhenPersistenceFails() {
		val file = Files.createTempFile("netless-content", ".db").toFile()
		val store = DurableEncryptedContentStore(file, PlainCipher)
		store.put("id", "old".encodeToByteArray())
		file.delete()
		file.mkdir()
		assertFailsWith<Exception> { store.put("id", "new".encodeToByteArray()) }
		assertContentEquals("old".encodeToByteArray(), store.get("id"))
	}
	@Test
	fun restoresEncryptedRecordsAfterReopen() {
		val file = Files.createTempFile("netless-content", ".db").toFile()
		val cipher = AesContentCipher()
		DurableEncryptedContentStore(file, cipher).put("id", "persisted".encodeToByteArray())

		assertContentEquals("persisted".encodeToByteArray(), DurableEncryptedContentStore(file, cipher).get("id"))
		file.delete()
	}
}
