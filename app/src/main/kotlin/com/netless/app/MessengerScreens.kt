package com.netless.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Button
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.*
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.*
import com.netless.protocol.DeliveryState

private val DeliveryState.resource: Int
	get() = when (this) {
		DeliveryState.Queued -> R.string.queued
		DeliveryState.Relaying -> R.string.relayed
		DeliveryState.Delivered -> R.string.delivered
		DeliveryState.Expired -> R.string.expired
		DeliveryState.Failed -> R.string.failed_try_again
	}

private enum class Tab(val label: Int, val item: NavigationItem) {
	Chats(R.string.tab_chats, NavigationItem("Chats", MiuixIcons.VerticalSplit)),
	Profile(R.string.tab_profile, NavigationItem("Profile", MiuixIcons.Contacts)),
	Settings(R.string.tab_settings, NavigationItem("Settings", MiuixIcons.Settings)),
}

@Composable
fun PrototypeApp(profile: ProfileUiState, state: MessengerUiState, messenger: MessengerViewModel, profileViewModel: ProfileViewModel) {
	val context = LocalContext.current
	val haptics = remember(context) { HapticController(context) }
	var tab by remember { mutableStateOf(Tab.Chats) }
	var query by remember { mutableStateOf("") }
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
				title = state.selectedConversation?.let { id -> state.conversations.firstOrNull { it.id == id }?.title } ?: when (tab) {
						Tab.Profile -> stringResource(R.string.tab_profile)
						Tab.Settings -> stringResource(R.string.tab_settings)
						else -> stringResource(R.string.app_title)
					},
				navigationIcon = { if (state.selectedConversation != null) IconButton({ haptics.perform(); messenger.closeConversation() }) { Icon(MiuixIcons.Back, stringResource(R.string.open_back)) } },
				)
			},
			floatingActionButton = { if (tab == Tab.Chats && state.selectedConversation == null && state.conversations.isNotEmpty()) IconButton({ haptics.perform(); messenger.selectConversation(state.conversations.first().id) }) { Icon(MiuixIcons.Add, stringResource(R.string.new_chat_accessibility)) } },
			bottomBar = {
				if (state.selectedConversation == null) {
					NavigationBar {
						Tab.entries.forEach { item -> NavigationBarItem(tab == item, { haptics.perform(); tab = item }, icon = item.item.icon, label = stringResource(item.label)) }
					}
				}
			},
		) { padding ->
			Box(Modifier.fillMaxSize().padding(padding)) {
				AnimatedContent(state.selectedConversation ?: tab, label = "screen") {
					when {
						state.selectedConversation != null -> Conversation(state, messenger, haptics)
						tab == Tab.Chats -> Chats(state.conversations, query, { query = it }) { haptics.perform(); messenger.selectConversation(it) }
						tab == Tab.Profile -> Profile(profile, profileViewModel, haptics)
						else -> Settings(theme, { haptics.perform(); theme = it }, relay, { haptics.perform(); relay = it }, store, { haptics.perform(); store = it }, notifications, { haptics.perform(); notifications = it }, policy, { haptics.perform(); policy = it }, transport, { haptics.perform(); transport = it }, priority, { haptics.perform(); priority = it }, hops, { haptics.perform(); hops = it }, unlimited, { haptics.perform(); unlimited = it }, ttl, { haptics.perform(); ttl = it }, profile.profile?.name.orEmpty(), profile.profile?.id?.value.orEmpty(), haptics)
					}
				}
			}
		}
	}
}

@Composable private fun Chats(items: List<ConversationUiState>, query: String, onQuery: (String) -> Unit, onOpen: (String) -> Unit) { Column(Modifier.fillMaxSize().padding(16.dp)) { Card { BasicTextField(query, onQuery, Modifier.fillMaxWidth().padding(16.dp), decorationBox = { inner -> if (query.isEmpty()) Text(stringResource(R.string.search)) else inner() }) }; LazyColumn { items(items.filter { it.title.contains(query, true) || it.preview.contains(query, true) }, key = { it.id }) { item -> Card(Modifier.fillMaxWidth().padding(bottom = 8.dp).animateContentSize().clickable { onOpen(item.id) }) { Column(Modifier.padding(12.dp)) { Text(item.title); Text(item.preview) } } } } } }
@Composable private fun Conversation(state: MessengerUiState, messenger: MessengerViewModel, haptics: HapticController) { val listState = rememberLazyListState(); LaunchedEffect(state.messages.size) { if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex) }; Column(Modifier.fillMaxSize().imePadding().padding(16.dp)) { LazyColumn(state = listState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) { items(state.messages, key = { it.id }) { message -> Card(Modifier.fillMaxWidth().animateContentSize()) { Column(Modifier.padding(14.dp)) { Text(message.body); AnimatedContent(message.deliveryState, label = "delivery") { Text(stringResource(it.resource)) } } } } }; Row(verticalAlignment = Alignment.CenterVertically) { BasicTextField(state.draft, messenger::draftChanged, Modifier.weight(1f).padding(12.dp).onFocusChanged { if (it.isFocused) haptics.perform() }, decorationBox = { inner -> if (state.draft.isEmpty()) Text(stringResource(R.string.message)) else inner() }); IconButton({ haptics.perform(); messenger.send() }) { Icon(MiuixIcons.Send, stringResource(R.string.send)) } } } }
@Composable private fun Profile(state: ProfileUiState, viewModel: ProfileViewModel, haptics: HapticController? = null) { Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text(stringResource(R.string.your_profile)); BasicTextField(state.name, viewModel::nameChanged, Modifier.onFocusChanged { if (it.isFocused) haptics?.perform() }); BasicTextField(state.bio, viewModel::bioChanged, Modifier.onFocusChanged { if (it.isFocused) haptics?.perform() }); Text(stringResource(R.string.persistent_identity)); state.profile?.let { Card { Text(stringResource(R.string.profile_id, it.id.value), Modifier.padding(16.dp)) } }; Button({ haptics?.perform(); viewModel.save() }) { Text(stringResource(R.string.save_profile)) } } }
@Composable private fun Choice(title: String, items: List<String>, selected: Int, onSelected: (Int) -> Unit) {
	OverlayDropdownPreference(items = items, selectedIndex = selected, title = title, onSelectedIndexChange = onSelected)
}
@Composable private fun Toggle(title: String, value: Boolean, onValue: (Boolean) -> Unit) { Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text(title, Modifier.weight(1f)); Switch(value, onValue) } }
@Composable private fun Settings(theme: Int, onTheme: (Int) -> Unit, relay: Boolean, onRelay: (Boolean) -> Unit, store: Boolean, onStore: (Boolean) -> Unit, notifications: Boolean, onNotifications: (Boolean) -> Unit, policy: Int, onPolicy: (Int) -> Unit, transport: Int, onTransport: (Int) -> Unit, priority: Int, onPriority: (Int) -> Unit, hops: Int, onHops: (Int) -> Unit, unlimited: Boolean, onUnlimited: (Boolean) -> Unit, ttl: Int, onTtl: (Int) -> Unit, profileName: String, profileId: String, haptics: HapticController) {
	val context = LocalContext.current
	val pick: @Composable (String, List<String>, Int, (Int) -> Unit) -> Unit = { title, values, selected, callback -> Card { Choice(title, values, selected, callback) } }
	LazyColumn(Modifier.fillMaxSize()) {
		item { Column(Modifier.padding(20.dp)) { Text(stringResource(R.string.tab_settings)); Text(profileName); Text(stringResource(R.string.profile_id, profileId)) } }
		item { SmallTitle(stringResource(R.string.general)) }
		item { Card { Choice(stringResource(R.string.app_language), listOf(stringResource(R.string.russian), stringResource(R.string.english)), 0) { context.startActivity(appLocaleSettingsIntent(context.packageName)) } } }
		item { pick(stringResource(R.string.theme), listOf(stringResource(R.string.light), stringResource(R.string.dark), stringResource(R.string.black)), theme, onTheme) }
		item { pick(stringResource(R.string.haptic_feedback), listOf(stringResource(R.string.haptic_off), stringResource(R.string.haptic_low), stringResource(R.string.haptic_medium), stringResource(R.string.haptic_high)), (haptics.strength / 25).coerceIn(0, 3)) { haptics.strength = it * 25; haptics.perform() } }
		item { SmallTitle(stringResource(R.string.message_delivery)) }
		item { pick(stringResource(R.string.connection), listOf(stringResource(R.string.automatic), stringResource(R.string.bluetooth), stringResource(R.string.wifi_direct), stringResource(R.string.wifi_aware), stringResource(R.string.local_hotspot)), transport, onTransport) }
		item { pick(stringResource(R.string.delivery_priority), listOf(stringResource(R.string.speed), stringResource(R.string.balanced), stringResource(R.string.coverage)), priority, onPriority) }
		item { Card { Toggle(stringResource(R.string.relay), relay, onRelay); Toggle(stringResource(R.string.store_and_forward), store, onStore) } }
		item { Card { Toggle(stringResource(R.string.notifications), notifications, onNotifications) } }
	}
}
