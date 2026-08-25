package com.netless.app

import com.netless.crypto.PublicKey
import com.netless.identity.IdentityRepository
import com.netless.transport.WifiDirectDataTransport
import com.netless.transport.SessionTransport
import com.netless.transport.TransportEndpoint
import java.nio.charset.StandardCharsets
import java.net.ServerSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

class PeerMessageRuntime(
	private val identity: IdentityRepository,
	private val messages: MessageRepository,
	private val wifi: WifiDirectDataTransport,
	private val wifiDiscovery: WifiDirectDiscoveryTransport? = null,
) {
	private var serverJob: Job? = null
	private var serverPort: Int = 0
	private var server: ServerSocket? = null

	fun startServer(scope: CoroutineScope, port: Int = 0): Int {
		if (serverJob != null) return serverPort
		server = ServerSocket(port)
		serverPort = server!!.localPort
		serverJob = scope.launch(Dispatchers.IO) {
			try {
				while (true) {
					val session = SessionTransport(server!!.accept())
					launch { accept(session) }
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
		serverJob = null
		serverPort = 0
	}

	private suspend fun accept(session: SessionTransport) {
		try {
			session.acceptAuthenticated(1, identity.getOrCreateIdentity().publicKey, identity::sign, identity::verify)
			session.packets().collect(::receive)
		} finally {
			session.close()
		}
	}

	suspend fun send(endpoint: com.netless.transport.TransportEndpoint, conversationId: String, body: String) {
		endpoint.metadata["identityKey"]?.let { PublicKey(java.util.Base64.getDecoder().decode(it)) }
			?: error("peer identity key is missing")
		try {
			val host = wifiDiscovery?.connectPeer(endpoint) ?: endpoint.address
			val connection = wifi.connectAuthenticated(TransportEndpoint(endpoint.nodeId, host, endpoint.metadata), 1, endpoint.metadata["sessionId"] ?: error("session id is missing"), identity.getOrCreateIdentity().publicKey, identity::sign, identity::verify)
			connection.send(MessageFrame.encode(conversationId, body))
			connection.close()
		} catch (error: java.io.IOException) {
			throw IllegalStateException("peer connection failed", error)
		}
	}

	fun receive(frame: ByteArray) {
		val message = MessageFrame.decode(frame)
		messages.send(message.conversationId, message.body)
	}
}

private data class PeerMessage(val conversationId: String, val body: String)

private object MessageFrame {
	fun encode(conversationId: String, body: String): ByteArray = "$conversationId\u0000$body".toByteArray(StandardCharsets.UTF_8)

	fun decode(bytes: ByteArray): PeerMessage {
		val parts = String(bytes, StandardCharsets.UTF_8).split('\u0000', limit = 2)
		require(parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) { "invalid message frame" }
		return PeerMessage(parts[0], parts[1])
	}
}
