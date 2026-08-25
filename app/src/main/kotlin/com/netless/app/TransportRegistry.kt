package com.netless.app

import com.netless.transport.TransportAdapter
import com.netless.transport.TransportType
import com.netless.transport.TransportState
import kotlinx.coroutines.flow.first

class TransportRegistry {
	private val adapters = LinkedHashMap<TransportType, TransportAdapter>()

	fun register(adapter: TransportAdapter) {
		adapters[adapter.type] = adapter
	}

	fun availableAdapters(): List<TransportAdapter> = adapters.values.toList()

	fun adapter(type: TransportType): TransportAdapter? = adapters[type]

	suspend fun available(type: TransportType): TransportAdapter? = adapters[type]?.takeIf {
		it.availability.first() !in setOf(TransportState.Unavailable, TransportState.Failed, TransportState.Closed)
	}
}
