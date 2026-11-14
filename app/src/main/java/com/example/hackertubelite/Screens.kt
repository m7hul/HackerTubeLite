@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.hackertubelite

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.List as ListIcon

// ───────────────────────── Home ─────────────────────────

@Composable
fun HomeScreen(vm: AppViewModel, onOpen: (String) -> Unit, onSearch: () -> Unit) {
    var type by rememberSaveable { mutableStateOf<String?>(null) }
    var sort by rememberSaveable { mutableStateOf(Sort.Top) }
    var seed by rememberSaveable { mutableIntStateOf(1) }
    var menuOpen by remember { mutableStateOf(false) }

    val items = remember(vm.videos, vm.hideNews, vm.includeCited, type, sort, seed) {
        vm.feed(type, sort, seed)
    }

    VideoGrid(
        videos = items,
        onOpen = onOpen,
        emptyMessage = "No videos match these filters.",
        header = {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Wordmark()
                    Spacer(Modifier.weight(1f))
                    Box {
                        TextButton(onClick = { menuOpen = true }) { Text(sort.label) }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            Sort.entries.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(s.label) },
                                    onClick = {
                                        sort = s
                                        if (s == Sort.Shuffle) seed++
                                        menuOpen = false
                                    },
                                )
                            }
                        }
                    }
                    IconButton(onClick = onSearch) {
                        Icon(Icons.Filled.Search, contentDescription = "Search")
                    }
                }
                Spacer(Modifier.height(4.dp))
                TypeChips(type) { type = it }
                Spacer(Modifier.height(8.dp))
            }
        },
    )
}

// ───────────────────────── Search ─────────────────────────

@Composable
fun SearchScreen(vm: AppViewModel, onBack: () -> Unit, onOpen: (String) -> Unit) {
    var q by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val results = remember(q, vm.videos, vm.hideNews, vm.includeCited) { vm.search(q) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
            TextField(
                value = q,
                onValueChange = { q = it },
                singleLine = true,
                placeholder = { Text("Search talks, topics, channels") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            if (q.isNotEmpty()) {
                IconButton(onClick = { q = "" }) { Icon(Icons.Filled.Close, contentDescription = "Clear") }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        VideoGrid(
            videos = results,
            onOpen = onOpen,
            emptyMessage = if (q.isBlank()) "Search by title, channel, topic or tag." else "No matches for “$q”.",
        )
    }
}

// ───────────────────────── Browse ─────────────────────────

@Composable
fun BrowseScreen(vm: AppViewModel, onCategory: (Cat) -> Unit, onSearch: () -> Unit) {
    var expanded by remember { mutableStateOf(setOf<String>()) }
    val counts = remember(vm.videos, vm.hideNews, vm.includeCited) { vm.categoryCounts() }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Browse", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, contentDescription = "Search") }
            }
        }
        vm.areas.forEach { area ->
            val open = area.id in expanded
            val total = area.c.sumOf { counts[it.id] ?: 0 }
            item(key = "area-" + area.id) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { expanded = if (open) expanded - area.id else expanded + area.id }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(area.n, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${area.c.size} topics",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (open) {
                items(area.c, key = { "cat-" + it.id }) { cat ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onCategory(cat) }
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(start = 32.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(cat.n, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(
                            "${counts[cat.id] ?: 0}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun CategoryScreen(vm: AppViewModel, cat: Cat, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val list = remember(cat.id, vm.videos, vm.hideNews, vm.includeCited) { vm.inCategory(cat.id) }
    VideoGrid(
        videos = list,
        onOpen = onOpen,
        emptyMessage = "No videos in this topic with the current filters.",
        header = {
            Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                Column(Modifier.weight(1f)) {
                    Text(cat.n, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${list.size} videos",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

// ───────────────────────── Library ─────────────────────────

@Composable
fun LibraryScreen(vm: AppViewModel, onOpen: (String) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val titles = listOf("Liked", "Watch later", "History")
    val ids = when (tab) {
        0 -> vm.liked
        1 -> vm.later
        else -> vm.history
    }
    val list = ids.mapNotNull { vm.byId[it] }
    val empty = when (tab) {
        0 -> "Videos you like will appear here."
        1 -> "Save videos to watch them later."
        else -> "Videos you open will appear here."
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            "Library",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        )
        TabRow(
            selectedTabIndex = tab,
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            titles.forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (tab == 2 && list.isNotEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { vm.clearHistory() }) { Text("Clear history") }
                    }
                }
            }
            if (list.isEmpty()) item { EmptyState(empty, Modifier.padding(top = 32.dp)) }
            items(list, key = { it.id }) { v ->
                VideoRow(
                    video = v,
                    onClick = { onOpen(v.id) },
                    onRemove = {
                        when (tab) {
                            0 -> vm.toggleLiked(v.id)
                            1 -> vm.toggleLater(v.id)
                            else -> vm.removeHistory(v.id)
                        }
                    },
                )
            }
        }
    }
}

// ───────────────────────── Settings ─────────────────────────

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val uri = LocalUriHandler.current
    var pending by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var importOpen by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var importMsg by remember { mutableStateOf("") }
    val version = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: ""
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        )

        SectionLabel("Appearance", Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { m ->
                AppChip(m.label, vm.themeMode == m) { vm.setTheme(m) }
            }
        }

        SectionLabel("Feed", Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp))
        SwitchRow(
            title = "Hide news-only videos",
            subtitle = "Videos that only mattered around one event.",
            checked = vm.hideNews,
            onChange = { vm.setHideNews(it) },
        )
        SwitchRow(
            title = "Include videos that were only cited",
            subtitle = "About a quarter of the catalog was linked on HN but never recommended.",
            checked = vm.includeCited,
            onChange = { vm.setIncludeCited(it) },
        )

        SectionLabel("Your data", Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp))
        Text(
            "No account and no analytics. Likes, watch-later, history and settings stay on this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        TextButton(
            onClick = { pending = "Clear your watch history?" to { vm.clearHistory() } },
            modifier = Modifier.padding(horizontal = 8.dp),
        ) { Text("Clear history") }
        TextButton(
            onClick = { pending = "Remove all likes and watch-later videos?" to { vm.clearSaved() } },
            modifier = Modifier.padding(horizontal = 8.dp),
        ) { Text("Clear likes and watch later") }

        SectionLabel("On-device AI (optional)", Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp))
        SwitchRow(
            title = "Smarter answers with a small AI model",
            subtitle = "Off by default. Turning it on downloads a model of about 100 MB from Hugging Face and loads code from jsDelivr, once. " +
                "Your questions and notes are never uploaded; they are processed on this phone. " +
                "Without it, answers come from HackerTube’s summaries and Hacker News comments and work fully offline.",
            checked = vm.neuralEnabled,
            onChange = { vm.enableNeural(it) },
        )
        if (vm.neuralEnabled) {
            Text(
                "Model status: " + NeuralEngine.status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        SectionLabel("Progress backup", Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp))
        Text(
            "Export your watched lessons, likes, watch-later list and notes as text, or import them on another phone.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Row(Modifier.padding(horizontal = 8.dp)) {
            TextButton(onClick = { shareText(ctx, "HackerTube progress", vm.exportJson()) }) { Text("Export") }
            TextButton(onClick = { importOpen = true }) { Text("Import") }
        }

        SectionLabel("About", Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp))
        val body = MaterialTheme.typography.bodyMedium
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        Text(
            "An unofficial Android reader for HackerTube, the YouTube videos that Hacker News keeps telling each other to watch. " +
                "Catalog snapshot: 31 October 2026.",
            style = body, color = muted, modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Catalog data from HackerTube (hackertube.org), licensed ODC-By 1.0, derived from the open-index/hacker-news dataset. " +
                "Hacker News data is provided by Y Combinator; comments remain the work of their authors. " +
                "Video titles and channel names belong to their creators.",
            style = body, color = muted, modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Videos play in YouTube’s official embedded player. Not affiliated with YouTube, Google, Hacker News, Y Combinator or HackerTube.",
            style = body, color = muted, modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Learning Paths, progress tracking and notes are inspired by HackerLearn by Preet Shukla (MIT licensed), " +
                "which pioneered structured roadmaps built from the HackerTube catalog. No code was copied; " +
                "the paths here are generated from the catalog’s topics and difficulty levels.",
            style = body, color = muted, modifier = Modifier.padding(horizontal = 16.dp),
        )
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            TextButton(onClick = { uri.openUri("https://hackertube.org") }) { Text("hackertube.org") }
            TextButton(onClick = { uri.openUri("https://opendatacommons.org/licenses/by/1-0/") }) { Text("License") }
            TextButton(onClick = { uri.openUri("https://github.com/PreetSHUKLA/hackerlearn") }) { Text("HackerLearn") }
        }
        if (version.isNotEmpty()) {
            Text(
                "Version $version",
                style = MaterialTheme.typography.bodySmall, color = muted,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }

    if (importOpen) {
        AlertDialog(
            onDismissRequest = { importOpen = false },
            title = { Text("Import progress") },
            text = {
                Column {
                    Text("Paste the text you exported earlier.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = importText,
                        onValueChange = { importText = it },
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                    )
                    if (importMsg.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(importMsg, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    importMsg = if (vm.importJson(importText)) "Imported." else "That doesn’t look like a HackerTube export."
                }) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = {
                    importOpen = false
                    importMsg = ""
                    importText = ""
                }) { Text("Close") }
            },
        )
    }

    pending?.let { (message, action) ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(message) },
            confirmButton = { TextButton(onClick = { action(); pending = null }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ───────────────────────── Video page ─────────────────────────

@Composable
fun DetailScreen(
    vm: AppViewModel,
    id: String,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onCategory: (Cat) -> Unit,
) {
    val video = vm.byId[id]
    if (video == null) {
        Column(Modifier.fillMaxSize()) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
            EmptyState("This video is no longer in the catalog.")
        }
        return
    }

    val ctx = LocalContext.current
    val uri = LocalUriHandler.current
    LaunchedEffect(id) { vm.pushHistory(id) }

    val isLiked = id in vm.liked
    val isSaved = id in vm.later
    val isWatched = id in vm.watched
    val rel = vm.related[id]
    val similar = remember(rel, vm.byId) { rel?.m.orEmpty().mapNotNull { vm.byId[it] } }
    val together = remember(rel, vm.byId) { rel?.p.orEmpty().mapNotNull { vm.byId[it] } }
    val comments = vm.mentions[id].orEmpty()
    val cats = remember(video, vm.areas) {
        val all = vm.areas.flatMap { it.c }.associateBy { it.id }
        video.cats.mapNotNull { all[it] }
    }

    Column(Modifier.fillMaxSize()) {
        IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
        YouTubePlayer(id, Modifier.fillMaxWidth().aspectRatio(16f / 9f))

        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
                    Text(video.title, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        video.channel,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        video.typeLabel() + " · " + video.hnLine() + if (video.lastYear > 0) " · ${video.years()}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    ActionItem(
                        if (isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        if (isLiked) "Liked" else "Like", isLiked, Modifier.weight(1f),
                    ) { vm.toggleLiked(id) }
                    ActionItem(
                        if (isSaved) Icons.Filled.Check else Icons.Filled.Add,
                        if (isSaved) "Saved" else "Watch later", isSaved, Modifier.weight(1f),
                    ) { vm.toggleLater(id) }
                    ActionItem(
                        if (isWatched) Icons.Filled.CheckCircle else Icons.Filled.Check,
                        if (isWatched) "Watched" else "Mark done", isWatched, Modifier.weight(1f),
                    ) { vm.toggleWatched(id) }
                    ActionItem(Icons.Filled.Share, "Share", false, Modifier.weight(1f)) { share(ctx, video) }
                    ActionItem(Icons.Filled.PlayArrow, "YouTube", false, Modifier.weight(1f)) {
                        uri.openUri(video.watchUrl())
                    }
                }
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (video.why.isNotBlank()) {
                item {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp)) {
                        SectionLabel("Why Hacker News watches this")
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.height(IntrinsicSize.Min)) {
                            Box(
                                Modifier
                                    .width(3.dp)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(Orange)
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(video.why, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
            if (video.summary.isNotBlank()) {
                item {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp)) {
                        SectionLabel("Summary")
                        Spacer(Modifier.height(8.dp))
                        Text(
                            video.summary,
                            style = MaterialTheme.typography.bodyLarge,
                            fontFamily = FontFamily.Serif,
                        )
                    }
                }
            }
            if (video.quote.isNotBlank()) {
                item {
                    val link = if (video.quoteId > 0) "https://news.ycombinator.com/item?id=${video.quoteId}" else null
                    Column(
                        Modifier
                            .padding(start = 16.dp, end = 16.dp, top = 20.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = link != null) { link?.let { uri.openUri(it) } }
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(16.dp)
                            .fillMaxWidth()
                    ) {
                        Text(
                            "“" + video.quote + "”",
                            style = MaterialTheme.typography.bodyLarge,
                            fontFamily = FontFamily.Serif,
                            fontStyle = FontStyle.Italic,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Hacker News" + if (video.quoteYear.isNotBlank()) ", ${video.quoteYear}" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (cats.isNotEmpty()) {
                item {
                    Row(
                        Modifier
                            .padding(top = 20.dp)
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        cats.forEach { c -> AppChip(c.n, false) { onCategory(c) } }
                    }
                }
            }
            item { AskPanel(vm, video, comments) }
            item { NotesSection(vm, id) }
            if (comments.isNotEmpty()) {
                item {
                    SectionLabel("From the comments", Modifier.padding(start = 16.dp, end = 16.dp, top = 28.dp))
                }
                items(comments) { m ->
                    val link = m.commentId.toLongOrNull()?.let { "https://news.ycombinator.com/item?id=$it" }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = link != null) { link?.let { uri.openUri(it) } }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Text(
                            stanceLabel(m.stance),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(m.text, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${m.author} · ${m.month}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item { Shelf("More like this", similar, onOpen) }
            item { Shelf("Recommended by the same people", together, onOpen) }
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun ActionItem(
    icon: ImageVector,
    label: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

private fun stanceLabel(stance: String): String = when (stance) {
    "strong_rec" -> "STRONGLY RECOMMENDED"
    "rec" -> "RECOMMENDED"
    "critical" -> "CRITICAL"
    else -> "MENTIONED"
}

fun shareText(ctx: Context, subject: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun share(ctx: Context, v: Video) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, v.title + "\n" + v.watchUrl())
    }
    ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Wordmark()
            Spacer(Modifier.height(24.dp))
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
        }
    }
}
