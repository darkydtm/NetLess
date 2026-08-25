package com.netless.database

import com.netless.common.NodeId
import com.netless.common.PacketId
import com.netless.protocol.DeliveryReceipt
import com.netless.protocol.DeliveryState
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
private const val MAX_PERSISTED_FILE_BYTES = 32 * 1024 * 1024
private const val MAX_PACKET_BYTES = 1024 * 1024
private const val STORAGE_MAGIC = 0x524C5931
private const val STORAGE_VERSION = 1

enum class RelayState {
	PENDING,
	TERMINAL,
}

class StoredRelayPacket(
	val packetId: PacketId,
	packet: ByteArray,
	val expiresAtMillis: Long,
	val nextHop: NodeId?,
	val finalDestination: NodeId?,
	val state: RelayState,
	val terminalReceipt: DeliveryReceipt? = null,
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
	private companion object {
		val processLock = Any()
	}

	private val records = LinkedHashMap<String, ByteArray>()
	private val deduplication = HashMap<String, Long>()

	init {
		withFileLock { load(); prune(nowMillis()) }
	}

	@Synchronized
	fun put(packet: ByteArray, packetId: PacketId, expiresAtMillis: Long, nextHop: NodeId?, finalDestination: NodeId? = null) {
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
			val serialized = serialize(packetId, packet, expiresAtMillis, nextHop, finalDestination)
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
	fun hasPending(packetId: PacketId): Boolean = withFileLock {
		load()
		prune(nowMillis())
		val stored = records[key(packetId)] ?: return@withFileLock false
		runCatching { deserialize(databaseKeyStore.unprotect(stored)).state == RelayState.PENDING }.getOrDefault(false)
	}

	@Synchronized
	fun contains(packetId: PacketId): Boolean = withFileLock { load(); prune(nowMillis()); deduplication.containsKey(key(packetId)) }

	@Synchronized
	fun markTerminal(packetId: PacketId, receipt: DeliveryReceipt) {
		withFileLock {
			load()
			val key = key(packetId)
			val stored = records[key] ?: error("unknown relay packet")
			val packet = deserialize(databaseKeyStore.unprotect(stored))
			require(packet.state == RelayState.PENDING) { "relay packet is already terminal" }
			require(receipt.packetId == packetId) { "receipt packet id does not match" }
			require(receipt.state == DeliveryState.Delivered) { "receipt is not terminal" }
			require(packet.finalDestination == null || receipt.nodeId == packet.finalDestination) { "receipt destination does not match" }
			require(receipt.timestampEpochMillis <= nowMillis() && receipt.timestampEpochMillis < packet.expiresAtMillis) { "receipt timestamp is invalid" }
			records[key] = databaseKeyStore.protect(serialize(packet.packetId, packet.packet, packet.expiresAtMillis, packet.nextHop, packet.finalDestination, receipt))
			persist()
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
		deduplication.size
	}

	private fun key(packetId: PacketId): String = "relay:${packetId.value}"

	private fun load() {
		val file = storageFile ?: return
		records.clear()
		deduplication.clear()
		if (!file.isFile || file.length() == 0L) return
		if (file.length() > MAX_PERSISTED_FILE_BYTES) return
		val loadedRecords = LinkedHashMap<String, ByteArray>()
		val loadedDeduplication = HashMap<String, Long>()
		var legacyFormat = false
		try {
			DataInputStream(file.inputStream().buffered()).use { input ->
				val header = input.readInt()
				val count = if (header == STORAGE_MAGIC) {
					require(input.readInt() == STORAGE_VERSION) { "unsupported relay storage version" }
					input.readInt()
				} else {
					legacyFormat = true
					header
				}
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
				require(input.read() == -1) { "trailing relay storage bytes" }
			}
		} catch (_: IOException) {
			return
		} catch (_: IllegalArgumentException) {
			return
		}
		loadedDeduplication.forEach { (storedKey, expiry) ->
			if (!storedKey.startsWith("relay:") || storedKey.length == "relay:".length) return@forEach
			val value = loadedRecords[storedKey]
			if (value == null) {
				deduplication[storedKey] = expiry
				return@forEach
			}
			try {
				val packet = deserialize(databaseKeyStore.unprotect(value), legacyFormat)
				require(storedKey == key(packet.packetId) && expiry == packet.expiresAtMillis)
				records[storedKey] = if (legacyFormat) databaseKeyStore.protect(serialize(packet.packetId, packet.packet, packet.expiresAtMillis, packet.nextHop, packet.finalDestination)) else value
				deduplication[storedKey] = expiry
			} catch (_: Exception) {
				return@forEach
			}
		}
		if (legacyFormat) persist()
	}

	private fun persist() {
		val file = storageFile ?: return
		require(deduplication.size <= MAX_PERSISTED_ENTRIES) { "too many relay entries" }
		records.values.forEach { require(it.size <= MAX_PERSISTED_VALUE_BYTES) { "relay value is too large" } }
		file.parentFile?.mkdirs()
		val temporary = File(file.path + ".tmp")
		temporary.outputStream().buffered().use { stream ->
			DataOutputStream(stream).use { output ->
				output.writeInt(STORAGE_MAGIC)
				output.writeInt(STORAGE_VERSION)
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

	private fun serialize(packetId: PacketId, packet: ByteArray, expiresAtMillis: Long, nextHop: NodeId?, finalDestination: NodeId? = null, receipt: DeliveryReceipt? = null): ByteArray =
		ByteArrayOutputStream().use { bytes ->
			DataOutputStream(bytes).use { output ->
				output.writeUTF(packetId.value)
				output.writeLong(expiresAtMillis)
				output.writeBoolean(nextHop != null)
				if (nextHop != null) output.writeUTF(nextHop.value)
				output.writeBoolean(finalDestination != null)
				if (finalDestination != null) output.writeUTF(finalDestination.value)
				output.writeBoolean(receipt != null)
				if (receipt != null) {
					output.writeUTF(receipt.state.name)
					output.writeUTF(receipt.nodeId.value)
					output.writeLong(receipt.timestampEpochMillis)
				}
				output.writeInt(packet.size)
				output.write(packet)
			}
			bytes.toByteArray()
		}

	private fun deserialize(value: ByteArray, legacy: Boolean = false): StoredRelayPacket = DataInputStream(ByteArrayInputStream(value)).use { input ->
		val packetId = PacketId(input.readUTF())
		val expiresAtMillis = input.readLong()
		val nextHop = if (input.readBoolean()) NodeId(input.readUTF()) else null
		val finalDestination = if (legacy) null else if (input.readBoolean()) NodeId(input.readUTF()) else null
		val receipt = if (!legacy && input.readBoolean()) DeliveryReceipt(packetId, DeliveryState.valueOf(input.readUTF()), NodeId(input.readUTF()), input.readLong()).also {
			require(it.state == DeliveryState.Delivered) { "invalid terminal receipt state" }
		} else null
		val size = input.readInt()
		require(size in 1..MAX_PACKET_BYTES) { "invalid packet size" }
		val packet = ByteArray(size).also(input::readFully)
		StoredRelayPacket(packetId, packet, expiresAtMillis, nextHop, finalDestination, if (receipt == null) RelayState.PENDING else RelayState.TERMINAL, receipt)
	}
}
