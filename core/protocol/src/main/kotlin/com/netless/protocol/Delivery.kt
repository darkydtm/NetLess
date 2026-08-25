package com.netless.protocol

import com.netless.common.NodeId
import com.netless.common.PacketId
import java.io.Serializable

enum class DeliveryState {
	Queued,
	Relaying,
	Delivered,
	Expired,
	Failed,
}

data class DeliveryReceipt(
	val packetId: PacketId,
	val state: DeliveryState,
	val nodeId: NodeId,
	val timestampEpochMillis: Long,
) : Serializable
