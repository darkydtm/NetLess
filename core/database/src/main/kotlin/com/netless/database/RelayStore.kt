package com.netless.database

import com.netless.common.NodeId
import com.netless.common.PacketId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

private const val MAX_PERSISTED_ENTRIES = 10_000
private const val MAX_PERSISTED_VALUE_BYTES = 2 * 1024 * 1024
private const val MAX_PACKET_BYTES = 1024 * 1024

private fun defaultRelayStorageFile(): File =
	File(System.getProperty("user.home", "."), ".netless/relay-store.bin")

enum class RelayState {
	PENDING,
}

class StoredRelayPacket(
	val packetId: PacketId,
	packet: ByteArray,
	val expiresAtMillis: Long,
	val nextHop: NodeId?,
	val state: RelayState,
) {
	private val packetValue = packet.copyOf()

	val packet: ByteArray
		get() = packetValue.copyOf()
}

class RelayStore(
	private val databaseKeyStore: DatabaseKeyStore = DatabaseKeyStore(),
	private val storageFile: File? = defaultRelayStorageFile(),
	private val nowMillis: () -> Long = System::currentTimeMillis,
) {
	private val records = LinkedHashMap<String, ByteArray>()
	private val deduplication = HashMap<String, Long>()

	init {
		load()
		expire(nowMillis())
	}

	@Synchronized
	fun put(packet: ByteArray, packetId: PacketId, expiresAtMillis: Long, nextHop: NodeId?) {
		require(packet.isNotEmpty()) { "packet must not be empty" }
		require(packet.size <= MAX_PACKET_BYTES) { "packet is too large" }
		val now = nowMillis()
		require(expiresAtMillis > now) { "packet must not be expired" }

		val key = key(packetId)
		expire(now)
		if (deduplication.containsKey(key)) return
		records[key] = databaseKeyStore.protect(serialize(packetId, packet, expiresAtMillis, nextHop))
		deduplication[key] = expiresAtMillis
		persist()
	}

	@Synchronized
	fun get(packetId: PacketId): StoredRelayPacket? {
		val key = key(packetId)
		val value = records[key] ?: return null
		return try {
			deserialize(databaseKeyStore.unprotect(value))
		} catch (_: Exception) {
			records.remove(key)
			deduplication.remove(key)
			persist()
			null
		}
	}

	@Synchronized
	fun markDelivered(packetId: PacketId) {
		records.remove(key(packetId))
		persist()
	}

	@Synchronized
	fun expire(nowMillis: Long): Int {
		val expired = deduplication.filterValues { it <= nowMillis }.keys
		var removed = 0
		expired.forEach {
			if (records.remove(it) != null) removed++
			deduplication.remove(it)
		}
		if (expired.isNotEmpty()) persist()
		return removed
	}

	@Synchronized
	fun count(): Int = records.size

	private fun key(packetId: PacketId): String = "relay:${packetId.value}"

	private fun load() {
		val file = storageFile ?: return
		if (!file.isFile || file.length() == 0L) return
		val loadedRecords = LinkedHashMap<String, ByteArray>()
		val loadedDeduplication = HashMap<String, Long>()
		try {
			DataInputStream(file.inputStream().buffered()).use { input ->
				val count = input.readInt()
				require(count in 0..MAX_PERSISTED_ENTRIES) { "invalid relay entry count" }
				repeat(count) {
					val key = input.readUTF()
					loadedDeduplication[key] = input.readLong()
					if (input.readBoolean()) {
						val size = input.readInt()
						require(size in 0..MAX_PERSISTED_VALUE_BYTES) { "invalid relay value size" }
						loadedRecords[key] = ByteArray(size).also(input::readFully)
					}
				}
			}
		} catch (_: IOException) {
			return
		} catch (_: IllegalArgumentException) {
			return
		}
		records.putAll(loadedRecords)
		deduplication.putAll(loadedDeduplication)
	}

	private fun persist() {
		val file = storageFile ?: return
		file.parentFile?.mkdirs()
		file.outputStream().buffered().use { stream ->
			DataOutputStream(stream).use { output ->
				output.writeInt(deduplication.size)
				deduplication.forEach { (key, expiry) ->
					output.writeUTF(key)
					output.writeLong(expiry)
					val value = records[key]
					output.writeBoolean(value != null)
					if (value != null) {
						output.writeInt(value.size)
						output.write(value)
					}
				}
			}
		}
	}

	private fun serialize(packetId: PacketId, packet: ByteArray, expiresAtMillis: Long, nextHop: NodeId?): ByteArray =
		ByteArrayOutputStream().use { bytes ->
			DataOutputStream(bytes).use { output ->
				output.writeUTF(packetId.value)
				output.writeLong(expiresAtMillis)
				output.writeBoolean(nextHop != null)
				if (nextHop != null) output.writeUTF(nextHop.value)
				output.writeInt(packet.size)
				output.write(packet)
			}
			bytes.toByteArray()
		}

	private fun deserialize(value: ByteArray): StoredRelayPacket = DataInputStream(ByteArrayInputStream(value)).use { input ->
		val packetId = PacketId(input.readUTF())
		val expiresAtMillis = input.readLong()
		val nextHop = if (input.readBoolean()) NodeId(input.readUTF()) else null
		val size = input.readInt()
		require(size in 1..MAX_PACKET_BYTES) { "invalid packet size" }
		val packet = ByteArray(size).also(input::readFully)
		StoredRelayPacket(packetId, packet, expiresAtMillis, nextHop, RelayState.PENDING)
	}
}
