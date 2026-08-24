package tech.ryadom.jabbit.demo.web

import kotlinx.browser.document
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement
import tech.ryadom.jabbit.JabbitBrowserOptions
import tech.ryadom.jabbit.JobInfo
import tech.ryadom.jabbit.JobState
import tech.ryadom.jabbit.createJabbit
import tech.ryadom.jabbit.demo.JabbitDemo
import tech.ryadom.jabbit.demo.contentToString
import tech.ryadom.jabbit.demo.demoConfiguration

private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

fun main() {
    val demo = JabbitDemo(
        createJabbit(
            configuration = demoConfiguration(),
            options = JabbitBrowserOptions(queueName = "demo")
        )
    )

    val root = document.getElementById("app") as HTMLElement
    root.appendChild(
        element("h1") { textContent = "Jabbit" }
    )
    root.appendChild(
        element("p") {
            className = "lead"
            textContent = "The queue lives in IndexedDB and survives a reload. " +
                    "Open a second tab: only one of them runs jobs, both show the same list."
        }
    )
    root.appendChild(actions(demo))

    val count = element("div") { className = "count" }
    val list = element("div") { className = "jobs" }
    root.appendChild(count)
    root.appendChild(list)

    scope.launch {
        demo.jobs.collect { jobs -> render(count, list, jobs) }
    }
}

private fun actions(demo: JabbitDemo): HTMLElement = element("div") {
    className = "actions"
    appendChild(button("Sync now") { demo.syncNow() })
    appendChild(button("Upload on Wi-Fi") { demo.uploadOverWifi() })
    appendChild(button("Upload that retries") { demo.uploadWithRetry() })
    appendChild(button("Clean while charging") { demo.cleanupWhileCharging() })
    appendChild(button("Every 6 hours") { demo.schedulePeriodicCleanup() })
    appendChild(button("Failing job") { demo.failingJob() })
    appendChild(button("Cancel all", secondary = true) { demo.cancelEverything() })
    appendChild(button("Clear finished", secondary = true) { demo.clearFinished() })
}

private fun render(count: HTMLElement, list: HTMLElement, jobs: List<JobInfo>) {
    count.textContent = if (jobs.isEmpty()) "No jobs yet" else "${jobs.size} jobs"
    list.innerHTML = ""

    jobs.forEach { job ->
        list.appendChild(
            element("div") {
                className = "job"
                appendChild(
                    element("span") {
                        className = "state"
                        style.backgroundColor = job.state.color()
                        textContent = job.state.name
                    }
                )
                appendChild(
                    element("div") {
                        appendChild(element("div") { textContent = job.contentToString() })
                        appendChild(
                            element("div") {
                                className = "id"
                                textContent = job.id.value.take(8)
                            }
                        )
                    }
                )
            }
        )
    }
}

private fun JobState.color(): String = when (this) {
    JobState.ENQUEUED -> "#9e9e9e"
    JobState.RUNNING -> "#1976d2"
    JobState.BLOCKED -> "#7b1fa2"
    JobState.SUCCEEDED -> "#2e7d32"
    JobState.FAILED -> "#c62828"
    JobState.CANCELLED -> "#ef6c00"
}

private fun button(
    label: String,
    secondary: Boolean = false,
    action: suspend () -> Unit
): HTMLElement = element("button") {
    textContent = label
    if (secondary) className = "secondary"
    addEventListener("click", { scope.launch { action() } })
}

private fun element(tag: String, build: HTMLElement.() -> Unit): HTMLElement =
    (document.createElement(tag) as HTMLElement).apply(build)
