package com.netless.app

import com.netless.common.TransferPolicy

object RuntimePolicy {
	const val maxNodes = 5
	val transferPolicy = TransferPolicy(maxHops = 2, relayEnabled = true)
}
