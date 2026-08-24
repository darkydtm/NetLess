package com.netless.transport

import com.netless.common.TrafficClass
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class AudioPolicyTest {
	@Test
	fun relayRejectsRealtimeAudio() {
		assertFalse(AudioRelayPolicy.mayRelay(TrafficClass.Realtime))
		assertTrue(AudioRelayPolicy.mayRelay(TrafficClass.Reliable))
		assertTrue(AudioRelayPolicy.mayRelay(TrafficClass.Bulk))
	}

	@Test
	fun radioModeUsesReliableTraffic() {
		assertEquals(TrafficClass.Reliable, AudioRelayPolicy.radioTrafficClass)
	}
}
