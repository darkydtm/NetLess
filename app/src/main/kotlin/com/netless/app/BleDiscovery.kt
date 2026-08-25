package com.netless.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import com.netless.common.NodeId
import com.netless.transport.DiscoveredNode
import com.netless.transport.DiscoveryAdvertisement
import com.netless.transport.DiscoveryCapability
import com.netless.transport.TransportCapabilities
import com.netless.transport.TransportEndpoint
import com.netless.transport.TransportType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.awaitClose

object BleAdvertisementCodec {
	private const val VERSION = 1
	private const val MAX_METADATA_ENTRIES = 32
	private const val MAX_METADATA_FIELD_BYTES = 512
	private const val MAX_PAYLOAD_BYTES = 8 * 1024

	fun encode(advertisement: DiscoveryAdvertisement): ByteArray = ByteArrayOutputStream().also { output ->
		DataOutputStream(output).use { data ->
			data.writeByte(VERSION)
			data.writeUTF(advertisement.discoveryHash)
			data.writeInt(advertisement.protocolVersion)
			data.writeUTF(advertisement.sessionId)
			data.writeInt(advertisement.capabilities.fold(0) { mask, capability -> mask or (1 shl capability.ordinal) })
			data.writeInt(advertisement.transportHints.fold(0) { mask, type -> mask or (1 shl type.ordinal) })
			data.writeInt(advertisement.metadata.size)
			advertisement.metadata.forEach { (key, value) -> data.writeUTF(key); data.writeUTF(value) }
		}
	}.toByteArray()

	fun decode(bytes: ByteArray): DiscoveryAdvertisement = DataInputStream(ByteArrayInputStream(bytes)).use { data ->
		require(bytes.size <= MAX_PAYLOAD_BYTES) { "Discovery advertisement is too large" }
		require(data.readUnsignedByte() == VERSION) { "Unsupported BLE advertisement version" }
		val discoveryHash = data.readUTF()
		val protocolVersion = data.readInt()
		val sessionId = data.readUTF()
		val capabilityMask = data.readInt()
		val transportMask = data.readInt()
		val metadataCount = data.readInt()
		require(metadataCount in 0..MAX_METADATA_ENTRIES) { "Too many discovery metadata entries" }
		val metadata = buildMap {
			repeat(metadataCount) {
				val key = data.readUTF(); val value = data.readUTF()
				require(key.encodeToByteArray().size <= MAX_METADATA_FIELD_BYTES && value.encodeToByteArray().size <= MAX_METADATA_FIELD_BYTES) { "Discovery metadata field is too large" }
				put(key, value)
			}
		}
		val capabilities = DiscoveryCapability.values().filter { capabilityMask and (1 shl it.ordinal) != 0 }
		val transportHints = TransportType.values().filter { transportMask and (1 shl it.ordinal) != 0 }
		DiscoveryAdvertisement(discoveryHash, protocolVersion, sessionId, capabilities.toSet(), transportHints.toSet(), metadata)
	}
}

class ContactStore(private val store: com.netless.content.DurableEncryptedContentStore? = null) {
	private val contactsByNode = LinkedHashMap<NodeId, DiscoveredNode>()
	private val _contacts = MutableStateFlow<List<DiscoveredNode>>(emptyList())
	val contacts: StateFlow<List<DiscoveredNode>> = _contacts.asStateFlow()
	init { store?.ids()?.filter { it.startsWith("discovered-contact:") }?.forEach { id -> store.get(id)?.let { runCatching { BleAdvertisementCodec.decode(it) }.getOrNull() }?.let { advertisement -> val nodeId = NodeId(advertisement.metadata["nodeId"] ?: return@let); val address = advertisement.metadata["endpointAddress"] ?: return@let; val endpoint = TransportEndpoint(nodeId, address, advertisement.metadata); upsert(DiscoveredNode(nodeId, endpoint, TransportCapabilities(true, true, 1, true, true))) } } }

	@Synchronized
	fun upsert(node: DiscoveredNode) {
		contactsByNode[node.nodeId] = node
		val metadata = node.endpoint.metadata + ("nodeId" to node.nodeId.value) + ("endpointAddress" to node.endpoint.address)
		store?.put("discovered-contact:${node.nodeId.value}", BleAdvertisementCodec.encode(DiscoveryAdvertisement(node.nodeId.value, 1, metadata["sessionId"] ?: node.nodeId.value, emptySet(), emptySet(), metadata)))
		_contacts.value = contactsByNode.values.toList()
	}

	fun upsert(profileId: com.netless.common.ProfileId, nodeId: NodeId, endpoint: TransportEndpoint, identityKey: String? = null) {
		require(endpoint.nodeId == nodeId)
		val metadata = endpoint.metadata.toMutableMap().apply {
			put("profileId", profileId.value)
			identityKey?.takeIf { it.isNotBlank() }?.let { put("identityKey", it) }
		}
		upsert(DiscoveredNode(nodeId, TransportEndpoint(nodeId, endpoint.address, metadata), TransportCapabilities(true, true, 1, true, true)))
	}

	fun upsert(profileId: com.netless.common.ProfileId, displayName: String, nodeId: NodeId, endpoint: TransportEndpoint, identityKey: String) {
		require(displayName.isNotBlank())
		upsert(profileId, nodeId, TransportEndpoint(nodeId, endpoint.address, endpoint.metadata + ("displayName" to displayName)), identityKey)
	}

	fun contact(profileId: String): DiscoveredNode? = contactsByNode.values.firstOrNull { it.endpoint.metadata["profileId"] == profileId }

	@Synchronized
	fun remove(nodeId: NodeId) {
		contactsByNode.remove(nodeId)
		_contacts.value = contactsByNode.values.toList()
	}
}

class AndroidBleDiscoveryTransport(context: Context) : com.netless.transport.DiscoveryTransport {
	private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
	private val scanner: BluetoothLeScanner?
		get() = adapter?.bluetoothLeScanner
	private val advertiser: BluetoothLeAdvertiser?
		get() = adapter?.bluetoothLeAdvertiser
	private var scanCallback: ScanCallback? = null
	private var advertiseCallback: AdvertiseCallback? = null
	private var localAdvertisement: DiscoveryAdvertisement? = null

	@SuppressLint("MissingPermission")
	override suspend fun startDiscovery() = callbackFlow {
		val bleScanner = scanner ?: error("Bluetooth LE scanner unavailable")
		val callback = object : ScanCallback() {
			override fun onScanResult(callbackType: Int, result: ScanResult) {
				val payload = result.scanRecord?.getManufacturerSpecificData(MANUFACTURER_ID) ?: return
				val advertisement = runCatching { BleAdvertisementCodec.decode(payload) }.getOrNull() ?: return
				val endpoint = TransportEndpoint(
					NodeId(advertisement.discoveryHash),
					result.device.address,
					advertisement.metadata + ("sessionId" to advertisement.sessionId),
				)
				trySend(
					DiscoveredNode(
						endpoint.nodeId,
						endpoint,
						TransportCapabilities(
							canAdvertise = DiscoveryCapability.Advertise in advertisement.capabilities,
							canAcceptIncoming = DiscoveryCapability.AcceptIncoming in advertisement.capabilities,
							maxConcurrentConnections = 1,
							supportsRelay = DiscoveryCapability.Relay in advertisement.capabilities,
							supportsLowLatency = DiscoveryCapability.LowLatency in advertisement.capabilities,
						),
					),
				)
			}
		}
		scanCallback = callback
		bleScanner.startScan(
			listOf(ScanFilter.Builder().setManufacturerData(MANUFACTURER_ID, byteArrayOf()).build()),
			ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
			callback,
		)
		awaitClose {
			bleScanner.stopScan(callback)
			if (scanCallback === callback) scanCallback = null
		}
	}

	@SuppressLint("MissingPermission")
	override suspend fun stopDiscovery() {
		scanCallback?.let { scanner?.stopScan(it) }
		scanCallback = null
	}

	@SuppressLint("MissingPermission")
	override suspend fun advertise(advertisement: DiscoveryAdvertisement) {
		localAdvertisement = advertisement
		val bleAdvertiser = advertiser ?: error("Bluetooth LE advertiser unavailable")
		advertiseCallback?.let { bleAdvertiser.stopAdvertising(it) }
		val settings = AdvertiseSettings.Builder()
			.setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
			.setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
			.setConnectable(false)
			.build()
		val data = AdvertiseData.Builder()
			.addManufacturerData(MANUFACTURER_ID, BleAdvertisementCodec.encode(advertisement))
			.setIncludeDeviceName(false)
			.build()
		val callback = object : AdvertiseCallback() {}
		advertiseCallback = callback
		bleAdvertiser.startAdvertising(settings, data, callback)
	}

	private companion object {
		const val MANUFACTURER_ID = 0xFFFF
	}
}
