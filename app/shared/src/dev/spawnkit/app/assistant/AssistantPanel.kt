package dev.spawnkit.app.assistant

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.spawnkit.ai.AgentOutcome
import dev.spawnkit.ai.DemoModel

/** The assistant: ask, review staged changes, then accept or reject them (C7c). */
@Composable
fun AssistantPanel(
    assistant: AssistantState,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(assistant.gameKey) { assistant.refresh() }
    var settings by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    // On phones the panel is short: bring the newest progress, question or proposal into view.
    LaunchedEffect(assistant.outcome, assistant.progress.size, assistant.notice) {
        // With nothing new to show (opened, accepted or rejected), go back to the examples and the prompt.
        if (assistant.outcome == null && assistant.progress.isEmpty() && assistant.notice == null) {
            scroll.animateScrollTo(0)
            return@LaunchedEffect
        }
        withFrameNanos { } // let the new content be measured first
        scroll.animateScrollTo(scroll.maxValue)
    }
    Column(
        modifier.verticalScroll(scroll).padding(12.dp).testTag("assistant"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                assistant.modelLabel,
                Modifier.weight(1f).testTag("assistant-model"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            TextButton({ settings = !settings }, Modifier.heightIn(min = 48.dp).testTag("assistant-settings")) {
                Text(if (settings) "Close settings" else "Settings")
            }
        }
        if (settings) KeySettings(assistant)
        if (!assistant.useLive) {
            Text("Try an example:", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DemoModel.PROMPTS.forEachIndexed { index, example ->
                    OutlinedButton({ assistant.prompt = example }, Modifier.heightIn(min = 48.dp).testTag("assistant-example-$index")) {
                        Text(example, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        OutlinedTextField(
            assistant.prompt,
            { assistant.prompt = it },
            Modifier.fillMaxWidth().testTag("assistant-prompt"),
            label = { Text("Ask the assistant") },
            placeholder = { Text("For example: make coins worth 5") },
            minLines = 2,
            enabled = !assistant.running,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(assistant::ask, Modifier.heightIn(min = 48.dp).testTag("assistant-ask"), enabled = assistant.canAsk()) { Text("Ask") }
            if (assistant.playing) Text("Stop the game to ask.", style = MaterialTheme.typography.bodySmall)
        }
        if (assistant.running) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Working…", Modifier.weight(1f))
                OutlinedButton(assistant::cancel, Modifier.heightIn(min = 48.dp).testTag("assistant-cancel")) { Text("Cancel") }
            }
        }
        assistant.progress.forEach { line ->
            Text("• $line", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when (val outcome = assistant.outcome) {
            is AgentOutcome.Proposal -> Proposal(assistant, outcome)
            is AgentOutcome.Answer -> Text(outcome.text, Modifier.testTag("assistant-answer"))
            is AgentOutcome.Question -> Question(assistant, outcome)
            is AgentOutcome.Failed, null -> Unit
        }
        assistant.notice?.let { Text(it, Modifier.testTag("assistant-notice"), color = MaterialTheme.colorScheme.tertiary) }
        Text(assistant.costText, Modifier.testTag("assistant-cost"), style = MaterialTheme.typography.labelSmall)
    }
}

/** Opens the assistant with a prompt about the selected object. */
@Composable
fun AskAiButton(onClick: () -> Unit) {
    OutlinedButton(onClick, Modifier.heightIn(min = 48.dp).testTag("ask-ai")) { Text("✨ Ask AI") }
}

@Composable
private fun Proposal(
    assistant: AssistantState,
    proposal: AgentOutcome.Proposal,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(proposal.summary, fontWeight = FontWeight.SemiBold)
            Text("Changes to review:", style = MaterialTheme.typography.bodySmall)
            proposal.steps.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            Text(
                "Nothing in your game has changed yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(assistant::accept, Modifier.heightIn(min = 48.dp).testTag("assistant-accept")) { Text("Accept") }
                OutlinedButton(assistant::reject, Modifier.heightIn(min = 48.dp).testTag("assistant-reject")) { Text("Reject") }
            }
        }
    }
}

@Composable
private fun Question(
    assistant: AssistantState,
    question: AgentOutcome.Question,
) {
    var reply by remember(question) { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(question.question, fontWeight = FontWeight.SemiBold)
        question.options.forEachIndexed { index, option ->
            OutlinedButton({ assistant.answer(option) }, Modifier.heightIn(min = 48.dp).testTag("assistant-option-$index")) { Text(option) }
        }
        OutlinedTextField(reply, { reply = it }, Modifier.fillMaxWidth().testTag("assistant-reply"), label = { Text("Or type an answer") })
        Button({ assistant.answer(reply) }, Modifier.heightIn(min = 48.dp).testTag("assistant-reply-send"), enabled = reply.isNotBlank()) {
            Text("Reply")
        }
    }
}

@Composable
private fun KeySettings(assistant: AssistantState) {
    var draft by remember { mutableStateOf("") }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Anthropic API key", fontWeight = FontWeight.SemiBold)
            Text(
                when {
                    !assistant.hasKey -> "Without a key, the scripted demo answers its example prompts. No AI model is called."
                    assistant.keyPersists -> "A key is saved in ${assistant.keyLocation}."
                    else -> "A key is saved and ${assistant.keyLocation}."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                draft,
                { draft = it },
                Modifier.fillMaxWidth().testTag("assistant-key"),
                label = { Text(if (assistant.hasKey) "Replace the key" else "Paste a key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    {
                        assistant.saveKey(draft)
                        draft = ""
                    },
                    Modifier.heightIn(min = 48.dp).testTag("assistant-key-save"),
                    enabled = draft.isNotBlank(),
                ) { Text("Save key") }
                if (assistant.hasKey) {
                    OutlinedButton(
                        assistant::removeKey,
                        Modifier.heightIn(min = 48.dp).testTag("assistant-key-remove"),
                    ) { Text("Remove key") }
                }
            }
            if (assistant.hasKey) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(assistant.useLive, { assistant.useLive = it }, Modifier.testTag("assistant-live"))
                    Spacer(Modifier.width(8.dp))
                    Text("Use the live model (uses tokens on your account)", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                "The key goes only to the model provider. It is never put in your game, exports or logs, and the assistant cannot read it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
