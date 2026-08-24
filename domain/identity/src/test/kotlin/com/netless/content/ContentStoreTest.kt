package com.netless.content

import kotlin.test.Test
import kotlin.test.assertEquals

class ContentStoreTest {
	@Test
	fun storesMessagesGroupsAndFilesById() {
		val store = ContentStore()
		store.putMessage(Message("m1", "chat", "hello"))
		store.putGroup(Group("chat", "Room"))
		store.putFile(FileAttachment("f1", "chat", "a.txt", byteArrayOf(1)))

		assertEquals("hello", store.messages("chat").single().body)
		assertEquals("Room", store.groups.single().name)
		assertEquals("a.txt", store.files("chat").single().name)
	}
}
