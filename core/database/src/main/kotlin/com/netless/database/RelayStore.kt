package com.netless.database

import com.netless.common.NodeId
import com.netless.common.PacketId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

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
	private val storageFile: File? = null,
	private val nowMillis: () -> Long = System::currentTimeMillis,
) {
	private val records = LinkedHashMap<String, ByteArray>()
	private val deduplication = HashMap<String, Long>()

	init {
		load()
	}

	@Synchronized
	fun put(packet: ByteArray, packetId: PacketId, expiresAtMillis: Long, nextHop: NodeId?) {
		require(packet.isNotEmpty()) { "packet must not be empty" }
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
	fun get(packetId: PacketId): StoredRelayPacket? = records[key(packetId)]?.let {
		deserialize(databaseKeyStore.unprotect(it))
	}

	@Synchronized
	fun markDelivered(packetId: PacketId) {
		records.remove(key(packetId))
		persist()
	}

	@Synchronized
	fun expire(nowMillis: Long): Int {
		val expired = deduplication.filterValues { it <= nowMillis }.keys
		expired.forEach {
			records.remove(it)
			deduplication.remove(it)
		}
		if (expired.isNotEmpty()) persist()
		return expired.size
	}

	@Synchronized
	fun count(): Int = records.size

	private fun key(packetId: PacketId): String = "relay:${packetId.value}"

	private fun load() {
		val file = storageFile ?: return
		if (!file.isFile || file.length() == 0L) return
		DataInputStream(file.inputStream().buffered()).use { input ->
			repeat(input.readInt()) {
				val key = input.readUTF()
				deduplication[key] = input.readLong()
				if (input.readBoolean()) records[key] = input.readBytes()
			}
		}
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
		val packet = ByteArray(input.readInt()).also(input::readFully)
		StoredRelayPacket(packetId, packet, expiresAtMillis, nextHop, RelayState.PENDING)
	}
}
