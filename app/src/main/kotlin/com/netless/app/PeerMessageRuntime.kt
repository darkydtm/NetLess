package com.netless.app

import com.netless.crypto.PublicKey
import com.netless.identity.IdentityRepository
import com.netless.transport.WifiDirectDataTransport
import java.nio.charset.StandardCharsets

class PeerMessageRuntime(
	private val identity: IdentityRepository,
	private val messages: MessageRepository,
	private val wifi: WifiDirectDataTransport,
) {
	suspend fun send(endpoint: com.netless.transport.TransportEndpoint, conversationId: String, body: String) {
		val peerKey = endpoint.metadata["identityKey"]?.let { PublicKey(java.util.Base64.getDecoder().decode(it)) }
			?: error("peer identity key is missing")
		val connection = wifi.connectAuthenticated(endpoint, 1, endpoint.metadata["sessionId"] ?: error("session id is missing"), identity.getOrCreateIdentity().publicKey, identity::sign, identity::verify)
		connection.send(MessageFrame.encode(conversationId, body))
		connection.close()
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
