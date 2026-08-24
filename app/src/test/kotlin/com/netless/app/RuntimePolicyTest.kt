package com.netless.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimePolicyTest {
	@Test
	fun defaultsMatchMeshSafetyRequirements() {
		assertEquals(5, RuntimePolicy.maxNodes)
		assertEquals(2, RuntimePolicy.transferPolicy.maxHops)
		assertTrue(RuntimePolicy.transferPolicy.relayEnabled)
	}
}
