package com.netless.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.theme.*

private enum class Tab(val ru: String, val en: String, val item: NavigationItem) {
	Chats("Чаты", "Chats", NavigationItem("Chats", MiuixIcons.VerticalSplit)),
	Profile("Профиль", "Profile", NavigationItem("Profile", MiuixIcons.Contacts)),
	Settings("Настройки", "Settings", NavigationItem("Settings", MiuixIcons.Settings)),
}

private data class Peer(val id: String, val name: String, val message: String, val transport: String, val online: Boolean)
private data class PrototypeMessage(val peerId: String, val text: String, val mine: Boolean)

private val peers = listOf(
	Peer("alex", "Alex", "Встретимся у моста?", "Wi-Fi Direct", true),
	Peer("nina", "Nina", "Маршрут через relay готов", "Bluetooth relay", true),
	Peer("team", "Team Mesh", "Проверим новый узел сегодня", "2 hops", false),
	Peer("mika", "Mika", "Я доступен рядом", "Wi-Fi Aware", true),
	Peer("sam", "Sam", "Профиль обновлён", "Bluetooth", true),
)

@Composable
fun PrototypeApp() {
	var tab by remember { mutableStateOf(Tab.Chats) }
	var selected by remember { mutableStateOf<String?>(null) }
	var query by remember { mutableStateOf("") }
	var messages by remember { mutableStateOf(listOf(PrototypeMessage("alex", "Привет! Я нашёл стабильный маршрут.", false))) }
	var russian by remember { mutableStateOf(true) }
	var theme by remember { mutableStateOf(0) }
	var relay by remember { mutableStateOf(true) }
	var store by remember { mutableStateOf(true) }
	var notifications by remember { mutableStateOf(true) }
	var policy by remember { mutableStateOf(0) }
	var transport by remember { mutableStateOf(0) }
	var priority by remember { mutableStateOf(1) }
	var hops by remember { mutableStateOf(2) }
	var unlimited by remember { mutableStateOf(false) }
	var ttl by remember { mutableStateOf(3) }
	val controller = remember(theme) {
		if (theme == 0) ThemeController(ColorSchemeMode.Light)
		else ThemeController(ColorSchemeMode.Dark, darkColors = if (theme == 2) darkColorScheme(background = Color.Black, surface = Color.Black) else darkColorScheme())
	}
	MiuixTheme(controller) {
		Scaffold(
			topBar = {
				TopAppBar(
					title = selected?.let { id -> peers.first { it.id == id }.name } ?: when (tab) {
						Tab.Profile -> if (russian) "Профиль" else "Profile"
						Tab.Settings -> if (russian) "Настройки" else "Settings"
						else -> "NetlessGram"
					},
					navigationIcon = { if (selected != null) IconButton({ selected = null }) { Icon(MiuixIcons.Back, "Back") } },
				)
			},
			floatingActionButton = { if (tab == Tab.Chats && selected == null) IconButton({ selected = peers.first().id }) { Icon(MiuixIcons.Add, "Новый чат") } },
			bottomBar = {
				if (selected == null) {
					NavigationBar {
						Tab.entries.forEach { item -> NavigationBarItem(tab == item, { tab = item }, icon = item.item.icon, label = if (russian) item.ru else item.en) }
					}
				}
			},
		) { padding ->
			Box(Modifier.fillMaxSize().padding(padding)) {
				when {
					selected != null -> Conversation(selected!!, messages, russian, { text -> messages += PrototypeMessage(selected!!, text, true) }) { index -> messages = messages.filterIndexed { position, message -> !(message.peerId == selected && position == index) } }
					tab == Tab.Chats -> Chats(query, { query = it }) { selected = it }
					tab == Tab.Profile -> Profile(russian)
					else -> Settings(russian, { russian = it }, theme, { theme = it }, relay, { relay = it }, store, { store = it }, notifications, { notifications = it }, policy, { policy = it }, transport, { transport = it }, priority, { priority = it }, hops, { hops = it }, unlimited, { unlimited = it }, ttl, { ttl = it })
				}
			}
		}
	}
}

@Composable private fun Chats(query: String, onQuery: (String) -> Unit, onOpen: (String) -> Unit) { Column(Modifier.fillMaxSize().padding(16.dp)) { Card { BasicTextField(query, onQuery, Modifier.fillMaxWidth().padding(16.dp), decorationBox = { inner -> if (query.isEmpty()) Text("Search") else inner() }) }; LazyColumn { items(peers.filter { it.name.contains(query, true) || it.message.contains(query, true) }) { peer -> ChatRow(peer, onOpen) } } } }
@Composable private fun ChatRow(peer: Peer, onOpen: (String) -> Unit) { Card(Modifier.fillMaxWidth().padding(bottom = 8.dp).clickable { onOpen(peer.id) }.semantics { contentDescription = "Open ${peer.name} conversation" }) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { Text(peer.name.first().toString(), color = Color.White, modifier = Modifier.size(48.dp).background(Color(0xFF7561A8), CircleShape).padding(14.dp)); Column(Modifier.weight(1f)) { Text(peer.name); Text(peer.message); Text(if (peer.online) "Connected · ${peer.transport}" else "Waiting for delivery") }; Text("10:42") } } }
@Composable private fun Conversation(id: String, messages: List<PrototypeMessage>, russian: Boolean, onSend: (String) -> Unit, onDelete: (Int) -> Unit) { var draft by remember { mutableStateOf("") }; var selectedIndex by remember { mutableStateOf<Int?>(null) }; Column(Modifier.fillMaxSize().padding(16.dp)) { Text(peers.first { it.id == id }.name, style = MiuixTheme.textStyles.title1); LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) { items(messages.filter { it.peerId == id }) { message -> val index = messages.indexOf(message); Card(Modifier.fillMaxWidth().clickable { selectedIndex = index }) { Text(message.text, Modifier.padding(14.dp)) } } }; selectedIndex?.let { index -> Row { TextButton("${if (russian) "Удалить" else "Delete"}", { onDelete(index); selectedIndex = null }) } }; Row(verticalAlignment = Alignment.CenterVertically) { BasicTextField(draft, { draft = it }, Modifier.weight(1f).padding(12.dp), decorationBox = { inner -> if (draft.isEmpty()) Text(if (russian) "Сообщение" else "Message") else inner() }); IconButton({ if (draft.isNotBlank()) { onSend(draft); draft = "" } }) { Icon(MiuixIcons.Send, "Send") } } } }
@Composable private fun Profile(russian: Boolean) { Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(if (russian) "Ваш профиль" else "Your profile"); Text("Alex", style = MiuixTheme.textStyles.title1); Text(if (russian) "Ваш постоянный идентификатор" else "Your persistent identity"); Card { Text("profile-alex-01", Modifier.padding(16.dp)) } } }
@Composable private fun Choice(title: String, items: List<String>, selected: Int, onSelected: (Int) -> Unit) {
	var expanded by remember { mutableStateOf(false) }
	Card(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
		Column(Modifier.padding(16.dp)) {
			Text(title)
			Text(items[selected])
			if (expanded) {
				items.forEachIndexed { index, item ->
					Text(item, Modifier.fillMaxWidth().clickable { onSelected(index); expanded = false }.padding(vertical = 12.dp))
				}
			}
		}
	}
}
@Composable private fun Toggle(title: String, value: Boolean, onValue: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text(title, Modifier.weight(1f)); Switch(value, onValue) } }
@Composable private fun Settings(russian: Boolean, onRussian: (Boolean) -> Unit, theme: Int, onTheme: (Int) -> Unit, relay: Boolean, onRelay: (Boolean) -> Unit, store: Boolean, onStore: (Boolean) -> Unit, notifications: Boolean, onNotifications: (Boolean) -> Unit, policy: Int, onPolicy: (Int) -> Unit, transport: Int, onTransport: (Int) -> Unit, priority: Int, onPriority: (Int) -> Unit, hops: Int, onHops: (Int) -> Unit, unlimited: Boolean, onUnlimited: (Boolean) -> Unit, ttl: Int, onTtl: (Int) -> Unit) {
	val pick: @Composable (String, List<String>, Int, (Int) -> Unit) -> Unit = { title, values, selected, callback -> Card { Choice(title, values, selected, callback) } }
	LazyColumn(Modifier.fillMaxSize()) {
		item { Column(Modifier.padding(20.dp)) { Text(if (russian) "Настройки" else "Settings"); Text("Alex"); Text("Profile ID: profile-alex-01") } }
		item { SmallTitle(if (russian) "Общие" else "General") }
		item { pick(if (russian) "Язык приложения" else "App language", listOf("Русский", "English"), if (russian) 0 else 1) { onRussian(it == 0) } }
		item { pick(if (russian) "Тема" else "Theme", listOf("Light", "Dark", "Black"), theme, onTheme) }
		item { SmallTitle(if (russian) "Как доставлять сообщения" else "Message delivery") }
		item { pick("Connection", listOf("Automatic", "Bluetooth", "Wi-Fi Direct", "Wi-Fi Aware", "Local Hotspot"), transport, onTransport) }
		item { pick("Delivery priority", listOf("Speed", "Balanced", "Coverage"), priority, onPriority) }
		item { Card { Toggle("Relay", relay, onRelay); Toggle("Store and forward", store, onStore) } }
		item { Card { Toggle("Notifications", notifications, onNotifications) } }
	}
}
