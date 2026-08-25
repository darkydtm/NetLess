package com.netless.database

import com.netless.common.NodeId
import com.netless.common.PacketId
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
		val store = RelayStore(DatabaseKeyStore(RecordingKeyWrapper()))
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
		val store = RelayStore(DatabaseKeyStore(RecordingKeyWrapper()))
		val bytes = byteArrayOf(1, 2, 3)

		store.put(bytes, packetId, 86_400_000L, nextHop)
		store.put(byteArrayOf(9), packetId, 90_000_000L, null)

		assertEquals(1, store.count())
		assertContentEquals(bytes, store.get(packetId)!!.packet)
	}

	@Test
	fun acknowledgementRemovesPacket() {
		val store = RelayStore(DatabaseKeyStore(RecordingKeyWrapper()))
		store.put(byteArrayOf(1), packetId, 86_400_000L, nextHop)

		store.markDelivered(packetId)

		assertNull(store.get(packetId))
		assertEquals(1, store.count())
	}

	@Test
	fun expiryRemovesExpiredPacketsAndReturnsCount() {
		val store = RelayStore(DatabaseKeyStore(RecordingKeyWrapper()))
		store.put(byteArrayOf(1), packetId, 100L, nextHop)
		store.put(byteArrayOf(2), PacketId("packet-2"), 200L, null)

		assertEquals(1, store.expire(100L))
		assertNull(store.get(packetId))
		assertEquals(1, store.count())
	}

	@Test
	fun rejectsBlankPacketAndExpiredInput() {
		val store = RelayStore(DatabaseKeyStore(RecordingKeyWrapper()))

		assertFailsWith<IllegalArgumentException> { store.put(byteArrayOf(), packetId, 1L, null) }
		assertFailsWith<IllegalArgumentException> { store.put(byteArrayOf(1), packetId, 0L, null) }
	}
}

private class RecordingKeyWrapper : KeyWrapper {
	override fun wrap(key: ByteArray): ByteArray = key.reversedArray()

	override fun unwrap(wrappedKey: ByteArray): ByteArray = wrappedKey.reversedArray()
}
