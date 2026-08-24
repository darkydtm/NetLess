package com.netless.transport

import com.netless.common.TrafficClass

object AudioRelayPolicy {
	val radioTrafficClass = TrafficClass.Reliable

	fun mayRelay(trafficClass: TrafficClass): Boolean = trafficClass != TrafficClass.Realtime
}
