package com.netless.transport

import com.netless.common.NodeId
import java.io.DataInputStream
import java.net.ServerSocket
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class WifiDirectTransportTest {
	@Test
	fun sendsAndReceivesLengthPrefixedPackets() = runBlocking {
		ServerSocket(0).use { server ->
			val transport = WifiDirectDataTransport()
			coroutineScope {
				val accepted = async {
					server.accept()
				}
				val connection = transport.connect(
					TransportEndpoint(NodeId("peer"), "127.0.0.1", mapOf("port" to server.localPort.toString())),
				)
				val socket = accepted.await()
				val packet = byteArrayOf(1, 2, 3)
				connection.send(packet)
				val input = DataInputStream(socket.inputStream)
				assertEquals(packet.size, input.readInt())
				assertContentEquals(packet, input.readNBytes(packet.size))
				socket.outputStream.write(byteArrayOf(0, 0, 0, 2, 9, 8))
				socket.outputStream.flush()
				assertContentEquals(byteArrayOf(9, 8), connection.incomingPackets.first())
				connection.close()
			}
		}
	}
}
