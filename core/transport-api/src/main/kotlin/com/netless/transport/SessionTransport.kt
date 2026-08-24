package com.netless.transport

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class SessionTransport(private val socket: Socket) {
	private val input = DataInputStream(socket.getInputStream())
	private val output = DataOutputStream(socket.getOutputStream())

	suspend fun establish(protocolVersion: Int, sessionId: String) {
		require(protocolVersion > 0 && sessionId.isNotBlank()) { "invalid session parameters" }
		synchronized(output) {
			output.writeInt(protocolVersion)
			output.writeUTF(sessionId)
			output.flush()
		}
	}

	fun packets(): Flow<ByteArray> = flow {
		while (!socket.isClosed) {
			val size = input.readInt()
			require(size in 0..MAX_PACKET_SIZE) { "packet exceeds session limit" }
			emit(input.readNBytes(size))
		}
	}

	suspend fun send(packet: ByteArray) {
		require(packet.size <= MAX_PACKET_SIZE) { "packet exceeds session limit" }
		synchronized(output) {
			output.writeInt(packet.size)
			output.write(packet)
			output.flush()
		}
	}

	private companion object { const val MAX_PACKET_SIZE = 4 * 1024 * 1024 }
}
