package com.netless.transport

import com.netless.crypto.PublicKey
import com.netless.crypto.Signature
import kotlinx.coroutines.flow.Flow

data class AuthenticatedConnectionRequest(
	val expectedPeerIdentity: PublicKey,
	val sessionId: String,
	val protocolVersion: Int = 1,
	val sign: suspend (ByteArray) -> Signature,
	val verify: suspend (PublicKey, ByteArray, Signature) -> Boolean,
)

interface TransportAdapter {
	val type: TransportType
	val availability: Flow<TransportState>

	suspend fun connect(endpoint: TransportEndpoint): TransportConnection

	suspend fun connectAuthenticated(endpoint: TransportEndpoint, request: AuthenticatedConnectionRequest): TransportConnection = connect(endpoint).also {
		require(it.peerIdentity?.encoded?.contentEquals(request.expectedPeerIdentity.encoded) == true) { "authenticated peer identity mismatch" }
	}

	fun supports(capability: DiscoveryCapability): Boolean
	fun fail() { }
}
