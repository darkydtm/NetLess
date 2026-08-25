package com.netless.transport

import java.io.Serializable
import java.util.Collections

sealed class TransportPolicy private constructor(
	val mode: TransportSelectionMode,
	preferences: List<TransportType>,
	val strictTransport: TransportType?,
	val relayAllowed: Boolean,
) : Serializable {
	val preferences: List<TransportType> = Collections.unmodifiableList(preferences.toList())
	val isStrict: Boolean
		get() = mode == TransportSelectionMode.Strict

	class Automatic(relayAllowed: Boolean = true) :
		TransportPolicy(TransportSelectionMode.Automatic, emptyList(), null, relayAllowed)

	class Preferred(preferences: List<TransportType>, relayAllowed: Boolean = true) :
		TransportPolicy(TransportSelectionMode.Preferred, preferences.distinct(), null, relayAllowed) {
		init {
			require(preferences.isNotEmpty()) { "preferences must not be empty" }
		}
	}

	class Strict(transport: TransportType?, relayAllowed: Boolean = true) :
		TransportPolicy(
			TransportSelectionMode.Strict,
			listOf(requireNotNull(transport)),
			requireNotNull(transport),
			relayAllowed,
		)
}

enum class TransportSelectionMode {
	Automatic,
	Preferred,
	Strict,
}
