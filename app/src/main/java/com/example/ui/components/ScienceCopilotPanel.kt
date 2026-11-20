package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.ai.GeminiCopilotClient
import com.example.ui.theme.CyanTelemetry
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun ScienceCopilotPanel(
    simulationContext: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Intentionally not saveable: credentials exist only while this panel is composed.
    var apiKey by remember { mutableStateOf("") }
    var question by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val history = remember { mutableStateListOf<GeminiCopilotClient.Turn>() }
    val scope = rememberCoroutineScope()

    GlassHudSurface(modifier = modifier.width(310.dp).heightIn(max = 560.dp)) {
        Column(
            modifier = Modifier.padding(12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = CyanTelemetry)
                Text("Science Copilot", style = MaterialTheme.typography.titleMedium, color = CyanTelemetry, modifier = Modifier.weight(1f).padding(start = 7.dp))
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close copilot", tint = TextSecondary) }
            }
            Text(
                "Optional Gemini 2.5 Flash. The key is used in memory only. Sending shares your question, the listed live values, and up to 8 prior turns with Google. The copilot cannot operate the simulation.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it; error = null },
                label = { Text("Gemini API key") },
                placeholder = { Text("Paste your key for this session") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Live context: ${simulationContext.lineSequence().firstOrNull().orEmpty()}",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
            OutlinedTextField(
                value = question,
                onValueChange = { question = it; error = null },
                label = { Text("Ask about this simulation") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )
            Button(
                enabled = !busy && apiKey.isNotBlank() && question.isNotBlank(),
                onClick = {
                    val prompt = question.trim()
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            val response = GeminiCopilotClient.ask(apiKey, simulationContext, history.toList(), prompt)
                            history.add(GeminiCopilotClient.Turn(prompt, response))
                            answer = response
                            question = ""
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            error = failure.message ?: "Could not reach Gemini. Check the connection and try again."
                        } finally {
                            busy = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            ) {
                if (busy) CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                else Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(if (busy) "Thinking…" else "Ask Copilot")
            }
            error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (answer.isNotBlank()) {
                Text("Answer", style = MaterialTheme.typography.titleSmall, color = CyanTelemetry)
                Text(answer, style = MaterialTheme.typography.bodySmall, color = TextPrimary)
            }
            Text(
                "AI can be wrong. Verify numerical claims; this is an explainer, not an ephemeris or physics solver.",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }
    }
}
