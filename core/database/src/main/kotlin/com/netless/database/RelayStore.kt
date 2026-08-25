package com.netless.database

import com.netless.common.NodeId
import com.netless.common.PacketId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
	private companion object {
		val processLock = Any()
	}

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

		withFileLock {
			load()
			prune(now)
			val key = key(packetId)
			if (deduplication.containsKey(key)) return@withFileLock
			if (deduplication.size >= MAX_PERSISTED_ENTRIES) throw IllegalArgumentException("too many relay entries")
			val serialized = serialize(packetId, packet, expiresAtMillis, nextHop)
			require(serialized.size <= MAX_PERSISTED_VALUE_BYTES) { "relay value is too large" }
			val value = databaseKeyStore.protect(serialized)
			require(value.size <= MAX_PERSISTED_VALUE_BYTES) { "relay value is too large" }
			records[key] = value
			deduplication[key] = expiresAtMillis
			persist()
		}
	}

	@Synchronized
	fun get(packetId: PacketId): StoredRelayPacket? {
		return withFileLock {
			load()
			prune(nowMillis())
			val key = key(packetId)
			val value = records[key] ?: return@withFileLock null
			try {
				val packet = deserialize(databaseKeyStore.unprotect(value))
				if (packet.packetId != packetId || packet.expiresAtMillis != deduplication[key] || packet.expiresAtMillis <= nowMillis()) {
					records.remove(key)
					deduplication.remove(key)
					persist()
					return@withFileLock null
				}
				packet
			} catch (_: Exception) {
				records.remove(key)
				deduplication.remove(key)
				persist()
				null
			}
		}
	}

	@Synchronized
	fun markDelivered(packetId: PacketId) {
		withFileLock {
			load()
			records.remove(key(packetId))
			persist()
		}
	}

	@Synchronized
	fun expire(nowMillis: Long): Int {
		return withFileLock {
			load()
			prune(nowMillis)
		}
	}

	@Synchronized
	fun count(): Int = withFileLock {
		load()
		records.size
	}

	private fun key(packetId: PacketId): String = "relay:${packetId.value}"

	private fun load() {
		val file = storageFile ?: return
		records.clear()
		deduplication.clear()
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
		loadedDeduplication.forEach { (key, expiry) ->
			val value = loadedRecords[key]
			if (value == null) {
				require(key.startsWith("relay:"))
				deduplication[key] = expiry
				return@forEach
			}
			try {
				val packet = deserialize(databaseKeyStore.unprotect(value))
				require(key == key(packet.packetId) && expiry == packet.expiresAtMillis)
				records[key] = value
				deduplication[key] = expiry
			} catch (_: Exception) {
				return@forEach
			}
		}
	}

	private fun persist() {
		val file = storageFile ?: return
		require(deduplication.size <= MAX_PERSISTED_ENTRIES) { "too many relay entries" }
		records.values.forEach { require(it.size <= MAX_PERSISTED_VALUE_BYTES) { "relay value is too large" } }
		file.parentFile?.mkdirs()
		val temporary = File(file.path + ".tmp")
		temporary.outputStream().buffered().use { stream ->
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
		try {
			Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
		} catch (_: IOException) {
			Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
		}
	}

	private fun prune(nowMillis: Long): Int {
		val expired = deduplication.filterValues { it <= nowMillis }.keys
		var removed = 0
		expired.forEach {
			if (records.remove(it) != null) removed++
			deduplication.remove(it)
		}
		if (expired.isNotEmpty()) persist()
		return removed
	}

	private fun <T> withFileLock(block: () -> T): T {
		return synchronized(processLock) {
			val file = storageFile ?: return@synchronized block()
			file.parentFile?.mkdirs()
			FileChannel.open(File(file.path + ".lock").toPath(), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE).use { channel ->
				channel.lock().use { block() }
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
