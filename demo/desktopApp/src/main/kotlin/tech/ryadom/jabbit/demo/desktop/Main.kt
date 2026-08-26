package tech.ryadom.jabbit.demo.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import tech.ryadom.jabbit.DesktopJabbit
import tech.ryadom.jabbit.JobInfo
import tech.ryadom.jabbit.JobState
import tech.ryadom.jabbit.demo.JabbitDemo
import tech.ryadom.jabbit.demo.contentToString
import tech.ryadom.jabbit.demo.createDemoJabbit

fun main() = application {
    val jabbit = remember { createDemoJabbit() }

    val demo = remember { JabbitDemo(jabbit) }

    Window(
        onCloseRequest = {
            (jabbit as? DesktopJabbit)?.close()
            exitApplication()
        },
        state = rememberWindowState(width = 900.dp, height = 700.dp),
        title = "Jabbit demo"
    ) {
        MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                DemoScreen(demo)
            }
        }
    }
}

@Composable
private fun DemoScreen(demo: JabbitDemo) {
    val scope = rememberCoroutineScope()
    val jobs by demo.jobs.collectAsState(emptyList())

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Jabbit",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "The queue is a file in the application data directory. " +
                "Close the window with jobs still pending and they resume on the next start.",
            style = MaterialTheme.typography.bodyMedium
        )

        Actions(demo, scope)

        Text(
            text = if (jobs.isEmpty()) "No jobs yet" else "${jobs.size} jobs",
            style = MaterialTheme.typography.labelLarge
        )

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(jobs, key = { it.id.value }) { job -> JobCard(job) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Actions(demo: JabbitDemo, scope: CoroutineScope) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Button(onClick = { scope.launch { demo.syncNow() } }) {
            Text("Sync now")
        }
        Button(onClick = { scope.launch { demo.uploadOverWifi() } }) {
            Text("Upload on Wi-Fi")
        }
        Button(onClick = { scope.launch { demo.uploadWithRetry() } }) {
            Text("Upload that retries")
        }
        Button(onClick = { scope.launch { demo.cleanupWhileCharging() } }) {
            Text("Clean while charging")
        }
        Button(onClick = { scope.launch { demo.schedulePeriodicCleanup() } }) {
            Text("Every 6 hours")
        }
        Button(onClick = { scope.launch { demo.failingJob() } }) {
            Text("Failing job")
        }
        OutlinedButton(onClick = { scope.launch { demo.cancelEverything() } }) {
            Text("Cancel all")
        }
        OutlinedButton(onClick = { scope.launch { demo.clearFinished() } }) {
            Text("Clear finished")
        }
    }
}

@Composable
private fun JobCard(job: JobInfo) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StateChip(job.state)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = job.contentToString(), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = job.id.value.take(8),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF757575)
                )
            }
        }
    }
}

@Composable
private fun StateChip(state: JobState) {
    val color = when (state) {
        JobState.ENQUEUED -> Color(0xFF9E9E9E)
        JobState.RUNNING -> Color(0xFF1976D2)
        JobState.BLOCKED -> Color(0xFF7B1FA2)
        JobState.SUCCEEDED -> Color(0xFF2E7D32)
        JobState.FAILED -> Color(0xFFC62828)
        JobState.CANCELLED -> Color(0xFFEF6C00)
    }

    Text(
        text = state.name,
        modifier = Modifier
            .background(color, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        color = Color.White,
        style = MaterialTheme.typography.labelSmall
    )
}
