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
		require(data.readUnsignedByte() == VERSION) { "Unsupported BLE advertisement version" }
		val discoveryHash = data.readUTF()
		val protocolVersion = data.readInt()
		val sessionId = data.readUTF()
		val capabilityMask = data.readInt()
		val transportMask = data.readInt()
		val metadata = buildMap {
			repeat(data.readInt()) { put(data.readUTF(), data.readUTF()) }
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
	init { store?.ids()?.filter { it.startsWith("discovered-contact:") }?.forEach { id -> store.get(id)?.let { runCatching { BleAdvertisementCodec.decode(it) }.getOrNull() }?.let { advertisement -> val endpoint = TransportEndpoint(NodeId(advertisement.discoveryHash), advertisement.discoveryHash, advertisement.metadata); upsert(DiscoveredNode(endpoint.nodeId, endpoint, TransportCapabilities(true, true, 1, true, true))) } } }

	@Synchronized
	fun upsert(node: DiscoveredNode) {
		contactsByNode[node.nodeId] = node
		store?.put("discovered-contact:${node.nodeId.value}", BleAdvertisementCodec.encode(DiscoveryAdvertisement(node.nodeId.value, 1, node.endpoint.metadata["sessionId"] ?: node.nodeId.value, emptySet(), emptySet(), node.endpoint.metadata)))
		_contacts.value = contactsByNode.values.toList()
	}

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
