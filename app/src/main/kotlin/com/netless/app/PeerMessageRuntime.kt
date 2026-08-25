package com.netless.app

import com.netless.transport.WifiDirectDataTransport
import com.netless.transport.SessionTransport
import com.netless.transport.TransportEndpoint
import java.net.ServerSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

class PeerMessageRuntime(
	private val receivePacket: suspend (ByteArray, com.netless.transport.TransportType) -> Unit,
	private val wifi: WifiDirectDataTransport,
	private val wifiDiscovery: WifiDirectDiscoveryTransport? = null,
	private val identityPublicKey: com.netless.crypto.PublicKey? = null,
	private val sign: (suspend (ByteArray) -> com.netless.crypto.Signature)? = null,
	private val verify: (suspend (com.netless.crypto.PublicKey, ByteArray, com.netless.crypto.Signature) -> Boolean)? = null,
	private val receiveFrame: (suspend (ByteArray, com.netless.transport.TransportType) -> ByteArray)? = null,
) {
	private var serverJob: Job? = null
	private var serverPort: Int = 0
	private var server: ServerSocket? = null
	private val sessionJobs = mutableSetOf<Job>()

	fun startServer(scope: CoroutineScope, port: Int = 0): Int {
		if (serverJob != null) return serverPort
		server = ServerSocket(port)
		serverPort = server!!.localPort
		serverJob = scope.launch(Dispatchers.IO) {
			try {
				while (true) {
					val session = SessionTransport(server!!.accept())
					sessionJobs += launch {
						try {
							require(identityPublicKey != null && sign != null && verify != null) { "authenticated session configuration is required" }
							session.acceptAuthenticated(1, identityPublicKey!!, sign!!, verify!!)
							accept(session)
						} catch (_: Exception) {
							session.close()
						} finally { sessionJobs.removeIf { it.isCompleted } }
					}
				}
			} catch (error: java.net.SocketException) {
				if (isActive) throw error
			} finally {
				server?.close()
				server = null
			}
		}
		return serverPort
	}

	fun port(): Int = serverPort

	fun stopServer() {
		server?.close()
		server = null
		serverJob?.cancel()
		sessionJobs.toList().forEach(Job::cancel)
		sessionJobs.clear()
		serverJob = null
		serverPort = 0
	}

	private suspend fun accept(session: SessionTransport) {
		try {
			session.packets().collect { packet ->
				val response = receiveFrame?.invoke(packet, com.netless.transport.TransportType.WifiDirect)
					?: receivePacket(packet, com.netless.transport.TransportType).let { null }
				if (response != null) session.send(response)
			}
		} finally {
			session.close()
		}
	}

}
