package com.netless.content

data class Message(val id: String, val conversationId: String, val body: String)
data class Group(val id: String, val name: String)
data class FileAttachment(val id: String, val conversationId: String, val name: String, val bytes: ByteArray)

class ContentStore {
	private val messageMap = LinkedHashMap<String, Message>()
	private val groupMap = LinkedHashMap<String, Group>()
	private val fileMap = LinkedHashMap<String, FileAttachment>()

	@Synchronized fun putMessage(message: Message) { messageMap[message.id] = message }
	@Synchronized fun putGroup(group: Group) { groupMap[group.id] = group }
	@Synchronized fun putFile(file: FileAttachment) { fileMap[file.id] = file }
	@Synchronized fun messages(conversationId: String): List<Message> = messageMap.values.filter { it.conversationId == conversationId }
	@Synchronized fun files(conversationId: String): List<FileAttachment> = fileMap.values.filter { it.conversationId == conversationId }
	@Synchronized val groups: List<Group> get() = groupMap.values.toList()
}
