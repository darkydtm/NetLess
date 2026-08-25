package com.netless.app

import com.netless.transport.TransportAdapter
import com.netless.transport.TransportType

class TransportRegistry {
	private val adapters = LinkedHashMap<TransportType, TransportAdapter>()

	fun register(adapter: TransportAdapter) {
		adapters[adapter.type] = adapter
	}

	fun availableAdapters(): List<TransportAdapter> = adapters.values.toList()

	fun adapter(type: TransportType): TransportAdapter? = adapters[type]
}
