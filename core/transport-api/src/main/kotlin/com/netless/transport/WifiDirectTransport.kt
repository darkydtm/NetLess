package com.netless.transport

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow

class WifiDirectDataTransport : DataTransport {
	override val type = TransportType.WifiDirect
	private val _state = MutableStateFlow(TransportState.Idle)
	override val state: Flow<TransportState> = _state.asStateFlow()

	override suspend fun connect(endpoint: TransportEndpoint): TransportConnection {
		val port = endpoint.metadata["port"]?.toIntOrNull()
		require(port != null && port in 1..65535) { "Wi-Fi Direct endpoint must contain a valid port" }
		_state.value = TransportState.Connecting
		return try {
			Socket().apply { connect(InetSocketAddress(endpoint.address, port)) }.also {
				_state.value = TransportState.Connected
			}
		}.let(::WifiDirectConnection)
	}

	private inner class WifiDirectConnection(private val socket: Socket) : TransportConnection {
		private val input = DataInputStream(socket.getInputStream())
		private val output = DataOutputStream(socket.getOutputStream())

		override val incomingPackets: Flow<ByteArray> = flow {
			while (!socket.isClosed) {
				val size = input.readInt()
				require(size in 0..MAX_PACKET_SIZE) { "Packet exceeds Wi-Fi Direct limit" }
				emit(input.readNBytes(size))
			}
		}

		override suspend fun send(packet: ByteArray) {
			require(packet.size <= MAX_PACKET_SIZE) { "Packet exceeds Wi-Fi Direct limit" }
			synchronized(output) {
				output.writeInt(packet.size)
				output.write(packet)
				output.flush()
			}
		}

		override suspend fun close() {
			socket.close()
			_state.value = TransportState.Closed
		}
	}

	private companion object {
		const val MAX_PACKET_SIZE = 4 * 1024 * 1024
	}
}
