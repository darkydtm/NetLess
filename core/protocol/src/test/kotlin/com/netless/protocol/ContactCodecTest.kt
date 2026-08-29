package com.netless.protocol

import com.netless.common.ProfileId
import com.netless.crypto.PublicKey
import com.netless.crypto.Signature
import com.netless.identity.Profile
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ContactCodecTest {
	@Test fun roundTripPreservesAllProfileFields() {
		val profile = Profile(ProfileId("profile"), PublicKey(byteArrayOf(1, 2)), "Name", "Bio", 7, Signature(byteArrayOf(3, 4)))
		assertEquals(profile, ContactCodec.decode(ContactCodec.encode(profile)))
	}
	@Test fun rejectsUnsupportedVersion() = assertFailsWith<IllegalArgumentException> { ContactCodec.decode(raw(2)) }
	@Test fun rejectsMalformedBase64() = assertFailsWith<IllegalArgumentException> { ContactCodec.decode("%%%") }
	@Test fun rejectsTruncatedData() = assertFailsWith<IllegalArgumentException> { ContactCodec.decode(Base64.getEncoder().encodeToString(raw(1).copyOf(3))) }
	@Test fun rejectsTrailingBytes() = assertFailsWith<IllegalArgumentException> { ContactCodec.decode(Base64.getEncoder().encodeToString(raw(1) + byteArrayOf(1))) }
	@Test fun rejectsOversizedFields() = assertFailsWith<IllegalArgumentException> { ContactCodec.decode(Base64.getEncoder().encodeToString(raw(1, 1_048_577))) }

	private fun raw(version: Int, fieldSize: Int = 1): ByteArray = ByteArrayOutputStream().also { DataOutputStream(it).use { output -> output.writeInt(version); output.writeInt(fieldSize); output.write(ByteArray(fieldSize)) } }.toByteArray()
}
