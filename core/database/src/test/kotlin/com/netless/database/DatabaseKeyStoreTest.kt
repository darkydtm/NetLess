package com.netless.database

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DatabaseKeyStoreTest {
	@Test
	fun createsRandomKeysThatRoundTripThroughTheWrapper() {
		val wrapper = DatabaseRecordingKeyWrapper()
		val store = DatabaseKeyStore(wrapper)
		val first = store.createWrappedKey()
		val second = store.createWrappedKey()

		assertFalse(first.wrappedKey.contentEquals(second.wrappedKey))
		assertTrue(first.keyId.isNotBlank())
		assertEquals(32, store.unwrap(first).size)
		assertTrue(store.unwrap(first).contentEquals(wrapper.original(first)))
	}

	@Test
	fun fileKeysRoundTripThroughTheWrapper() {
		val wrapper = DatabaseRecordingKeyWrapper()
		val store = FileKeyStore(wrapper)
		val key = store.createWrappedKey()

		assertTrue(store.unwrap(key).contentEquals(wrapper.original(key)))
	}
}

private class DatabaseRecordingKeyWrapper : KeyWrapper {
	private val originals = mutableMapOf<List<Byte>, ByteArray>()

	override fun wrap(key: ByteArray): ByteArray = key.reversedArray().also { originals[it.toList()] = key.copyOf() }

	override fun unwrap(wrappedKey: ByteArray): ByteArray = wrappedKey.reversedArray()

	fun original(key: WrappedDatabaseKey): ByteArray = original(key.wrappedKey)

	fun original(key: WrappedFileKey): ByteArray = original(key.wrappedKey)

	private fun original(wrappedKey: ByteArray): ByteArray = originals[wrappedKey.toList()] ?: error("missing key")
}
