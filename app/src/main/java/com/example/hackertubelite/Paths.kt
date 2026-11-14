@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.hackertubelite

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private const val HACKERLEARN_URL = "https://github.com/PreetSHUKLA/hackerlearn"

@Composable
fun PathsScreen(vm: AppViewModel, onOpen: (String) -> Unit) {
    val uri = LocalUriHandler.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)) {
                Text("Paths", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Courses built from the catalog: topics in order, easiest first. Tick lessons off as you go.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                )
            }
        }
        if (vm.paths.isEmpty()) item { EmptyState("Paths are not available.") }
        items(vm.paths, key = { it.id }) { p ->
            val (done, total) = vm.progress(p)
            PathCard(p, done, total) { onOpen(p.id) }
        }
        item {
            TextButton(
                onClick = { uri.openUri(HACKERLEARN_URL) },
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            ) { Text("Inspired by HackerLearn by Preet Shukla") }
        }
    }
}

@Composable
private fun PathCard(path: LearningPath, done: Int, total: Int, onClick: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(16.dp)
    ) {
        Text(path.n, style = MaterialTheme.typography.titleMedium)
        if (path.t.isNotBlank()) {
            Text(path.t, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "$total lessons · ${path.m.size} modules · ${path.lv}",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { if (total == 0) 0f else done.toFloat() / total },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (done == 0) "Not started" else "$done of $total watched",
            style = MaterialTheme.typography.bodySmall,
            color = muted,
        )
    }
}

@Composable
fun PathDetailScreen(vm: AppViewModel, id: String, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val path = vm.paths.firstOrNull { it.id == id }
    if (path == null) {
        Column(Modifier.fillMaxSize()) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
            EmptyState("This path is not available.")
        }
        return
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val (done, total) = vm.progress(path)
    val next = vm.nextLesson(path)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Column {
                IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(path.n, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "$total lessons · ${path.m.size} modules · ${path.lv}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = muted,
                    )
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { if (total == 0) 0f else done.toFloat() / total },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("$done of $total watched", style = MaterialTheme.typography.bodySmall, color = muted)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = {
                        val target = next ?: vm.lessonIds(path).firstOrNull()
                        if (target != null) onOpen(target)
                    }) {
                        Text(
                            when {
                                next == null -> "Completed · watch again"
                                done == 0 -> "Start"
                                else -> "Continue"
                            }
                        )
                    }
                }
            }
        }
        path.m.forEachIndexed { index, module ->
            item(key = "module-" + module.id) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp)) {
                    SectionLabel("Module ${index + 1}")
                    Spacer(Modifier.height(2.dp))
                    Text(module.n, style = MaterialTheme.typography.titleMedium)
                }
            }
            items(module.l, key = { "lesson-" + module.id + "-" + it }) { vid ->
                val v = vm.byId[vid]
                if (v != null) {
                    LessonRow(v, vid in vm.watched, onClick = { onOpen(vid) }, onToggle = { vm.toggleWatched(vid) })
                }
            }
        }
    }
}

@Composable
private fun LessonRow(video: Video, done: Boolean, onClick: () -> Unit, onToggle: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumb(video, Modifier.width(112.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(
                video.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(video.levelWord(), video.typeLabel()).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
        }
        IconButton(onClick = onToggle) {
            Icon(
                if (done) Icons.Filled.CheckCircle else Icons.Filled.Check,
                contentDescription = if (done) "Mark as not watched" else "Mark as watched",
                tint = if (done) MaterialTheme.colorScheme.primary else muted.copy(alpha = 0.4f),
            )
        }
    }
}
