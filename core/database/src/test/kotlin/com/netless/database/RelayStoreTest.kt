package com.netless.database

import com.netless.common.NodeId
import com.netless.common.PacketId
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RelayStoreTest {
	private val packetId = PacketId("packet-1")
	private val nextHop = NodeId("node-2")

	@Test
	fun storesOpaquePacketAndMetadata() {
		val store = RelayStore(DatabaseKeyStore(RecordingKeyWrapper()), storageFile = null, nowMillis = { 0L })
		val bytes = byteArrayOf(1, 2, 3)

		store.put(bytes, packetId, 86_400_000L, nextHop)

		val stored = store.get(packetId)!!
		assertEquals(packetId, stored.packetId)
		assertContentEquals(bytes, stored.packet)
		assertEquals(86_400_000L, stored.expiresAtMillis)
		assertEquals(nextHop, stored.nextHop)
		assertEquals(RelayState.PENDING, stored.state)
	}

	@Test
	fun duplicatePacketDoesNotCreateASecondRelayEntry() {
		val store = RelayStore(DatabaseKeyStore(RecordingKeyWrapper()), storageFile = null, nowMillis = { 0L })
		val bytes = byteArrayOf(1, 2, 3)

		store.put(bytes, packetId, 86_400_000L, nextHop)
		store.put(byteArrayOf(9), packetId, 90_000_000L, null)

		assertEquals(1, store.count())
		assertContentEquals(bytes, store.get(packetId)!!.packet)
	}

	@Test
	fun acknowledgementRemovesPacket() {
		val store = RelayStore(DatabaseKeyStore(RecordingKeyWrapper()), storageFile = null, nowMillis = { 0L })
		store.put(byteArrayOf(1), packetId, 86_400_000L, nextHop)

		store.markDelivered(packetId)

		assertNull(store.get(packetId))
		assertEquals(1, store.count())
	}

	@Test
	fun expiryRemovesExpiredPacketsAndReturnsCount() {
		val store = RelayStore(DatabaseKeyStore(RecordingKeyWrapper()), storageFile = null, nowMillis = { 0L })
		store.put(byteArrayOf(1), packetId, 100L, nextHop)
		store.put(byteArrayOf(2), PacketId("packet-2"), 200L, null)

		assertEquals(1, store.expire(100L))
		assertNull(store.get(packetId))
		assertEquals(1, store.count())
	}

	@Test
	fun expiryCountsOnlyPacketsActuallyRemoved() {
		val store = RelayStore(DatabaseKeyStore(RecordingKeyWrapper()), storageFile = null, nowMillis = { 0L })
		store.put(byteArrayOf(1), packetId, 100L, nextHop)
		store.put(byteArrayOf(2), PacketId("packet-2"), 100L, null)
		store.markDelivered(packetId)

		assertEquals(1, store.expire(100L))
		assertEquals(0, store.count())
	}

	@Test
	fun rejectsBlankPacketAndExpiredInput() {
		val store = RelayStore(DatabaseKeyStore(RecordingKeyWrapper()), storageFile = null, nowMillis = { 100L })

		assertFailsWith<IllegalArgumentException> { store.put(byteArrayOf(), packetId, 1L, null) }
		assertFailsWith<IllegalArgumentException> { store.put(byteArrayOf(1), packetId, 100L, null) }
		assertFailsWith<IllegalArgumentException> { store.put(ByteArray(1024 * 1024 + 1), packetId, 200L, null) }
	}

	@Test
	fun persistsAcrossStoreRecreation() {
		val file = File.createTempFile("relay-store", ".bin")
		try {
			file.delete()
			val keyStore = DatabaseKeyStore(RecordingKeyWrapper())
			RelayStore(keyStore, file, nowMillis = { 100L }).put(byteArrayOf(1, 2), packetId, 200L, nextHop)

			val restored = RelayStore(keyStore, file, nowMillis = { 100L })

			assertContentEquals(byteArrayOf(1, 2), restored.get(packetId)!!.packet)
		} finally {
			file.delete()
		}
	}

	@Test
	fun explicitStoragePersistsAcrossStoreRecreation() {
		val file = File.createTempFile("relay-store", ".bin")
		try {
			val keyStore = DatabaseKeyStore(RecordingKeyWrapper())
			RelayStore(keyStore, file).put(byteArrayOf(1), packetId, Long.MAX_VALUE, nextHop)

			assertContentEquals(byteArrayOf(1), RelayStore(keyStore, file).get(packetId)!!.packet)
		} finally {
			file.delete()
			File(file.path + ".lock").delete()
		}
	}

	@Test
	fun refusesToWriteBeyondPersistedEntryBound() {
		val file = File.createTempFile("relay-store", ".bin")
		try {
			DataOutputStream(file.outputStream()).use { output ->
				output.writeInt(10_000)
				repeat(10_000) {
					output.writeUTF("relay:packet-$it")
					output.writeLong(100L)
					output.writeBoolean(false)
				}
			}
			val store = RelayStore(DatabaseKeyStore(RecordingKeyWrapper()), file, nowMillis = { 0L })

			assertFailsWith<IllegalArgumentException> {
				store.put(byteArrayOf(1), PacketId("packet-10000"), 100L, null)
			}
		} finally {
			file.delete()
			File(file.path + ".lock").delete()
		}
	}

	@Test
	fun getHidesExpiredPacket() {
		var now = 0L
		val store = RelayStore(DatabaseKeyStore(RecordingKeyWrapper()), storageFile = null, nowMillis = { now })
		store.put(byteArrayOf(1), packetId, 100L, null)
		now = 100L

		assertNull(store.get(packetId))
	}

	@Test
	fun ignoresStoredMetadataThatDoesNotMatchItsKey() {
		val file = File.createTempFile("relay-store", ".bin")
		try {
			val keyStore = DatabaseKeyStore(RecordingKeyWrapper())
			val value = ByteArrayOutputStream().use { bytes ->
				DataOutputStream(bytes).use { output ->
					output.writeUTF("other")
					output.writeLong(100L)
					output.writeBoolean(false)
					output.writeInt(1)
					output.writeByte(1)
				}
				bytes.toByteArray()
			}
			DataOutputStream(file.outputStream()).use { output ->
				output.writeInt(1)
				output.writeUTF("relay:${packetId.value}")
				output.writeLong(100L)
				output.writeBoolean(true)
				output.writeInt(keyStore.protect(value).size)
				output.write(keyStore.protect(value))
			}

			assertEquals(0, RelayStore(keyStore, file, nowMillis = { 0L }).count())
		} finally {
			file.delete()
		}
	}

	@Test
	fun expiredDeduplicationDoesNotBlockReinsertion() {
		var now = 100L
		val store = RelayStore(DatabaseKeyStore(RecordingKeyWrapper()), storageFile = null, nowMillis = { now })
		store.put(byteArrayOf(1), packetId, 100L, nextHop)
		now = 101L

		store.put(byteArrayOf(2), packetId, 200L, nextHop)

		assertContentEquals(byteArrayOf(2), store.get(packetId)!!.packet)
	}

	@Test
	fun malformedStorageFileIsIgnored() {
		val file = File.createTempFile("relay-store", ".bin")
		try {
			file.writeBytes(byteArrayOf(0, 0, 0, 1))

			val store = RelayStore(DatabaseKeyStore(RecordingKeyWrapper()), file, nowMillis = { 0L })

			assertEquals(0, store.count())
		} finally {
			file.delete()
		}
	}

	@Test
	fun malformedPersistedKeyIsIgnored() {
		val file = File.createTempFile("relay-store", ".bin")
		try {
			DataOutputStream(file.outputStream()).use { output ->
				output.writeInt(1)
				output.writeUTF("not-a-relay-key")
				output.writeLong(100L)
				output.writeBoolean(false)
			}

			assertEquals(0, RelayStore(DatabaseKeyStore(RecordingKeyWrapper()), file, nowMillis = { 0L }).count())
		} finally {
			file.delete()
		}
	}

	@Test
	fun rejectsOversizedPersistedEntryCountBeforeAllocation() {
		val file = File.createTempFile("relay-store", ".bin")
		try {
			DataOutputStream(file.outputStream()).use { it.writeInt(Int.MAX_VALUE) }

			val store = RelayStore(DatabaseKeyStore(RecordingKeyWrapper()), file, nowMillis = { 0L })

			assertEquals(0, store.count())
		} finally {
			file.delete()
		}
	}

	@Test
	fun rejectsOversizedPersistedPacketBeforeAllocation() {
		val file = File.createTempFile("relay-store", ".bin")
		try {
			DataOutputStream(file.outputStream()).use { output ->
				output.writeInt(1)
				output.writeUTF("relay:packet-1")
				output.writeLong(100L)
				output.writeBoolean(true)
				output.writeInt(Int.MAX_VALUE)
			}

			val store = RelayStore(DatabaseKeyStore(RecordingKeyWrapper()), file, nowMillis = { 0L })

			assertEquals(0, store.count())
		} finally {
			file.delete()
		}
	}

	@Test
	fun rejectsOversizedPersistedFileBeforeReadingEntries() {
		val file = File.createTempFile("relay-store", ".bin")
		try {
			file.setLength(32L * 1024 * 1024 + 1)

			assertEquals(0, RelayStore(DatabaseKeyStore(RecordingKeyWrapper()), file, nowMillis = { 0L }).count())
		} finally {
			file.delete()
		}
	}
}

private class RecordingKeyWrapper : KeyWrapper {
	override fun wrap(key: ByteArray): ByteArray = key.reversedArray()

	override fun unwrap(wrappedKey: ByteArray): ByteArray = wrappedKey.reversedArray()
}
