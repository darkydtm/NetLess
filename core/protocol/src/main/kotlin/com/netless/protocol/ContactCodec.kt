package com.netless.protocol

import com.netless.crypto.PublicKey
import com.netless.crypto.Signature
import com.netless.identity.Profile
import com.netless.common.ProfileId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64

object ContactCodec {
	private const val VERSION = 1

	fun encode(profile: Profile): String = Base64.getEncoder().encodeToString(ByteArrayOutputStream().also {
		DataOutputStream(it).use { output ->
			output.writeInt(VERSION)
			write(output, profile.id.value)
			write(output, profile.publicKey.encoded)
			write(output, profile.name)
			write(output, profile.bio)
			output.writeLong(profile.version)
			write(output, profile.signature.bytes)
		}
	}.toByteArray())

	fun decode(value: String): Profile = runCatching {
		val bytes = Base64.getDecoder().decode(value)
		DataInputStream(ByteArrayInputStream(bytes)).use { input ->
			require(input.readInt() == VERSION) { "Unsupported contact version" }
			val profile = Profile(
				ProfileId(readString(input)),
				PublicKey(readBytes(input)),
				readString(input),
				readString(input),
				input.readLong(),
				Signature(readBytes(input)),
			)
			require(input.available() == 0) { "Trailing contact data" }
			profile
		}
	}.getOrElse { throw IllegalArgumentException("Invalid contact data", it) }

	private fun write(output: DataOutputStream, value: String) = write(output, value.encodeToByteArray())
	private fun write(output: DataOutputStream, value: ByteArray) { require(value.isNotEmpty()); output.writeInt(value.size); output.write(value) }
	private fun readString(input: DataInputStream) = readBytes(input).decodeToString()
	private fun readBytes(input: DataInputStream): ByteArray { val size = input.readInt(); require(size in 1..1_048_576); return ByteArray(size).also(input::readFully) }
}
