package com.netless.app

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pConfig
import com.netless.common.NodeId
import com.netless.transport.DiscoveredNode
import com.netless.transport.DiscoveryAdvertisement
import com.netless.transport.DiscoveryTransport
import com.netless.transport.TransportCapabilities
import com.netless.transport.TransportEndpoint
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@SuppressLint("MissingPermission")
class WifiDirectDiscoveryTransport(private val context: Context) : DiscoveryTransport {
	private val manager = context.getSystemService(WifiP2pManager::class.java)
	private val wifiChannel = manager?.initialize(context, context.mainLooper, null)
	private var receiver: BroadcastReceiver? = null

	override suspend fun startDiscovery() = callbackFlow {
		val wifiManager = manager ?: error("Wi-Fi Direct unavailable")
		val channel = wifiChannel ?: error("Wi-Fi Direct channel unavailable")
		val callback = object : WifiP2pManager.PeerListListener {
			override fun onPeersAvailable(list: WifiP2pDeviceList) {
				list.deviceList.forEach { device -> trySend(device.toDiscoveredNode()) }
			}
		}
		val registered = object : BroadcastReceiver() {
			override fun onReceive(context: Context, intent: Intent) {
				if (intent.action == WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION) {
					wifiManager.requestPeers(channel, callback)
				}
			}
		}
		receiver = registered
		context.registerReceiver(registered, IntentFilter(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION))
		wifiManager.discoverPeers(wifiChannel, object : WifiP2pManager.ActionListener {
			override fun onSuccess() = Unit
			override fun onFailure(reason: Int) { close(IllegalStateException("Wi-Fi Direct discovery failed: $reason")) }
		})
		awaitClose {
			context.unregisterReceiver(registered)
			if (receiver === registered) receiver = null
		}
	}

	override suspend fun stopDiscovery() {
		val wifiManager = manager ?: return
		val channel = wifiChannel ?: return
		wifiManager.stopPeerDiscovery(channel, null)
		receiver?.let { context.unregisterReceiver(it) }
		receiver = null
	}

	override suspend fun advertise(advertisement: DiscoveryAdvertisement) = Unit

	suspend fun connectPeer(endpoint: TransportEndpoint): String {
		val wifiManager = manager ?: error("Wi-Fi Direct unavailable")
		val channel = wifiChannel ?: error("Wi-Fi Direct channel unavailable")
		val address = endpoint.address
		wifiManager.connect(channel, WifiP2pConfig().apply { deviceAddress = address }, actionResult())
		return suspendCancellableCoroutine { continuation ->
			wifiManager.requestConnectionInfo(channel) { info ->
				val host = info.groupOwnerAddress?.hostAddress
				if (host != null) continuation.resume(host) else continuation.resumeWithException(IllegalStateException("Wi-Fi Direct group is not ready"))
			}
		}
	}

	private fun actionResult() = object : WifiP2pManager.ActionListener {
		override fun onSuccess() = Unit
		override fun onFailure(reason: Int) = Unit
	}

	private fun WifiP2pDevice.toDiscoveredNode() = DiscoveredNode(
		NodeId(deviceAddress),
		TransportEndpoint(NodeId(deviceAddress), deviceAddress, mapOf("deviceName" to deviceName)),
		TransportCapabilities(false, true, 1, true, true),
	)
}
