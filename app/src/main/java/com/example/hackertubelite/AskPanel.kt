@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.hackertubelite

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class ChatMsg(val fromUser: Boolean, val text: String, val engine: String = "")

/** "Ask about this video": answers come from HackerTube's notes; an optional on-device model can phrase them. */
@Composable
fun AskPanel(vm: AppViewModel, video: Video, comments: List<Mention>) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var expanded by remember(video.id) { mutableStateOf(false) }
    var input by remember(video.id) { mutableStateOf("") }
    var busy by remember(video.id) { mutableStateOf(false) }
    val messages = remember(video.id) { mutableStateListOf<ChatMsg>() }

    LaunchedEffect(expanded, vm.neuralEnabled) {
        if (expanded && vm.neuralEnabled) NeuralEngine.start(ctx)
    }

    fun ask(q: String) {
        val question = q.trim()
        if (question.isEmpty() || busy) return
        messages.add(ChatMsg(true, question))
        input = ""
        busy = true
        scope.launch {
            val c = AiContext(video, comments)
            var text: String? = null
            var engine = "From HackerTube notes"
            if (vm.neuralEnabled && NeuralEngine.ready) {
                val out = NeuralEngine.ask(GroundedAi.prompt(question, c))
                if (out != null && out.length > 12) {
                    text = out
                    engine = "On-device AI"
                }
            }
            val answer = text ?: withContext(Dispatchers.Default) { GroundedAi.answer(question, c) }
            messages.add(ChatMsg(false, answer, engine))
            busy = false
        }
    }

    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            .padding(start = 16.dp, end = 16.dp, top = 24.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Ask about this video", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (vm.neuralEnabled) "On-device AI: " + NeuralEngine.status else "Offline · no network used",
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
            }
            Icon(
                if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = muted,
            )
        }
        if (expanded) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    GroundedAi.quick.forEach { (label, q) -> AppChip(label, false) { ask(q) } }
                }
                messages.forEach { m ->
                    Column(
                        Modifier.fillMaxWidth().padding(top = 10.dp),
                        horizontalAlignment = if (m.fromUser) Alignment.End else Alignment.Start,
                    ) {
                        Column(
                            Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (m.fromUser) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surface
                                )
                                .padding(12.dp)
                        ) {
                            Text(m.text, style = MaterialTheme.typography.bodyMedium)
                            if (m.engine.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Text(m.engine, style = MaterialTheme.typography.labelSmall, color = muted)
                            }
                        }
                    }
                }
                if (busy) {
                    Text(
                        "Thinking…",
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        singleLine = true,
                        placeholder = { Text("Ask a question") },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { ask(input) }),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { ask(input) }) { Text("Ask") }
                }
                Text(
                    "Answers use HackerTube’s summary and Hacker News comments, not the video’s audio or transcript.",
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
fun NotesSection(vm: AppViewModel, id: String) {
    var text by remember(id) { mutableStateOf(vm.note(id)) }
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp)) {
        SectionLabel("Your notes")
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                vm.saveNote(id, it)
            },
            placeholder = { Text("Jot down what you learned…") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
