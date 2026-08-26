# Jabbit

Kotlin Multiplatform job runner for Android, iOS, the desktop and the browser, with an API
like in `androidx.work.WorkManager`.

Jobs are declarative: you describe *what* to run, *how often*, and *under which device conditions*,
and the platform decides *when*. On Android that decision is delegated to `WorkManager`. Everywhere
else, where no equivalent system service exists, Jabbit persists the queue itself and drives it from
the app, using whatever background windows the platform grants.

- One API in `commonMain`, no `expect`/`actual` in your own code beyond obtaining the instance.
- Jobs survive process death, reboots and page reloads.
- Constraints on network, metering, charging, battery, storage and device idle.
- One-time and periodic jobs, unique jobs, tags, retries with exponential or linear backoff,
  input/output payloads, progress, and observable state as `Flow`.

Generated API dokka: **https://ryadomtech.github.io/jabbit-kmp-job-runner**

## Installation

```kotlin
// build.gradle.kts of your shared module
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("tech.ryadom:jabbit:2.0.0")
        }
    }
}
```

Targets: `android`, `iosX64`, `iosArm64`, `iosSimulatorArm64`, `jvm`, `js`, `wasmJs`.

Minimum versions: Android API 23, iOS 13, Java 11 on the desktop, and any browser with
IndexedDB.

## Writing a worker

A job is described by a [`JobType`](jabbit/src/commonMain/kotlin/tech/ryadom/jabbit/JobType.kt): a
stable name plus the payloads it takes and returns. Declare one per worker and share it between the
code that registers the worker and the code that enqueues jobs, so the compiler keeps both in step.

```kotlin
@Serializable
data class SyncInput(val since: Long)

@Serializable
data class SyncOutput(val items: Int)

val SyncJob = jobType<SyncInput, SyncOutput>("sync")

class SyncWorker(private val api: Api) : JabbitWorker<SyncInput, SyncOutput> {

    override suspend fun doWork(job: JobExecution<SyncInput>): JobResult<SyncOutput> {
        return try {
            job.setProgress(JobProgress(fraction = 0.5f, message = "syncing"))
            JobResult.success(SyncOutput(items = api.sync(job.input.since)))
        } catch (e: IOException) {
            JobResult.retry()
        }
    }
}
```

Payloads travel through `kotlinx.serialization`, because a job outlives the process that enqueued
it — so the module declaring them needs the serialization plugin. Use `Unit` for a job that needs
no input, no output, or neither: `jobType<Unit, Unit>("cleanup")`.

The name in `jobType` is what gets persisted, so it has to stay stable across releases. Unlike a
class name it survives obfuscation and refactoring.

A worker is created anew for every run, so keep it stateless and take its dependencies through the
constructor.

Asking for a retry keeps the job alive for as long as it wants, unless the request caps it:
`setMaxAttempts(5)` gives up after the fifth start and finishes the job as `FAILED`. The cap counts
every start, including the ones the system cut short. `JobResult.failure("...")` ends the job for
good and the reason is readable through `JobInfo.failureReason`.

`doWork` is cancelled cooperatively when the platform stops the job — when constraints stop
holding, when the job is cancelled, or when an iOS background window expires. A cancelled job
returns to `ENQUEUED` and runs again later, so check `isActive` inside long loops.

## Creating the scheduler

Describe the whole thing once, in common code:

```kotlin
val jabbit = jabbit {
    worker(SyncJob) { SyncWorker(api) }
    worker(CleanupJob) { CleanupWorker(database) }
    logger(JabbitLogger.Console)

    android { }

    ios {
        backgroundTaskIdentifier = "com.example.app.jabbit"
    }

    desktop {
        applicationName = "Example"
    }

    browser {
        queueName = "example"
    }
}
```

Only the block matching the platform running the code is read, so the same call compiles and runs
everywhere. Nothing has to be handed in from platform code — not even the Android `Context`, which
Jabbit picks up from its own initialization provider before any application code runs. Android
therefore needs no settings at all; its block is there so shared setup reads the same everywhere.

Call it once at startup, from wherever the app builds its dependencies. On Android that has to
happen in the process `WorkManager` runs jobs in, which in practice means `Application.onCreate` or
a dependency graph built there.

On the desktop the returned instance is also a `DesktopJabbit`, which is `AutoCloseable`: cast to it
when the application wants to stop the scheduler and release the single instance lock.

## Running jobs after the last tab closes

The browser only lets a closed app do work through a service worker. Build the worker script as its
own Kotlin/JS or Kotlin/Wasm bundle and start Jabbit from it with the **same** description the page
uses — it has to be able to build the same workers, and read the same queue:

```kotlin
// sw.kt, bundled separately and registered as the service worker
fun main() {
    startJabbitServiceWorker {
        worker(SyncJob) { SyncWorker(api) }

        browser {
            queueName = "example"
            periodicSyncTag = "com.example.refresh"
        }
    }
}
```

It listens for two events, both Chromium-only:

- `sync` — one shot, fired once connectivity returns after the app went offline.
- `periodicsync` — fired on a cadence the browser picks, only for an installed app it considers
  engaging, and only after the `periodic-background-sync` permission is granted.

Neither fires in Safari or Firefox; there, jobs run while a page is open and wait otherwise.
Nothing throws when the events are unavailable — the registrations are skipped. While any page of
the app is open, the worker stands aside and lets the page run the queue, so a job is never started
twice.

The storage in the `browser { }` block must be reachable from a worker, which rules out
`LocalStorageJabbitStorage`. The default IndexedDB storage is fine.

## Enqueueing jobs

```kotlin
jabbit.enqueue(
    oneTimeJob(SyncJob, SyncInput(since = lastSyncAt)) {
        setConstraints(constraints { requiredNetworkType = NetworkType.CONNECTED })
        setInitialDelay(10.seconds)
        setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30.seconds)
        setMaxAttempts(5)
        addTag("sync")
    }
)
```

Work that must not pile up gets a unique name:

```kotlin
jabbit.enqueueUnique(
    uniqueName = "sync",
    policy = ExistingJobPolicy.KEEP,
    request = oneTimeJob(SyncJob, SyncInput(since = lastSyncAt))
)
```

`KEEP` leaves an unfinished job alone and drops the new request. `REPLACE` does the opposite: the
job that was there is forgotten — its identifier stops resolving rather than turning up later as
`CANCELLED`.

Periodic work is declared the same way. Calling this on every start with `KEEP` is the usual way to
guarantee the job exists exactly once:

```kotlin
jabbit.enqueueUniquePeriodic(
    uniqueName = "cleanup",
    policy = ExistingPeriodicJobPolicy.KEEP,
    request = periodicJob(CleanupJob, repeatInterval = 6.hours) {
        setConstraints(
            constraints {
                requiresCharging = true
                requiresBatteryNotLow = true
            }
        )
    }
)
```

## Observing and cancelling

```kotlin
jabbit.getJobInfosByTagFlow("sync").collect { infos ->
    val running = infos.firstOrNull { it.state == JobState.RUNNING }
    val fraction = running?.progress?.fraction

    val items = infos.firstOrNull { it.state == JobState.SUCCEEDED }?.output(SyncJob)?.items
}

jabbit.cancelJobsByTag("sync")
```

`JobInfo` carries the state, the tags, the current progress, why the job failed, the number of
attempts so far, and when the next run is planned. `output(type)` reads what the job produced,
decoded with the type it was enqueued under; a payload written by a different type reads back as
`null` rather than throwing.

## Watching what happens

Install a listener to route the lifecycle of every job into logging, analytics or crash reporting:

```kotlin
val jabbit = jabbit {
    worker(SyncJob) { SyncWorker(api) }

    listener(object : JabbitListener {
        override fun onFailed(job: JobInfo, error: Throwable?) {
            crashReporter.report("job ${job.typeName} failed: ${job.failureReason}", error)
        }

        override fun onRetryScheduled(job: JobInfo, delayMillis: Long?) {
            analytics.log("retry", job.typeName, attempt = job.runAttemptCount)
        }
    })
}
```

Every method has an empty body, so an implementation overrides only what it needs: `onEnqueued`,
`onStarted`, `onSucceeded`, `onFailed`, `onRetryScheduled`, `onStopped` and `onCancelled`.
`onEnqueued` always arrives before `onStarted`; the rest follow the job. A listener that throws is
logged and skipped rather than allowed to break the scheduler, and callbacks arrive on whichever
thread the job changed on, so they should stay quick.

`onStopped` is the one worth knowing about: the platform cut a running job short — constraints
stopped holding, an iOS background window expired, or the process went away — and it will run
again.

On Android `WorkManager` also cancels work on its own, after `cancelAllWork()` or when application
data is cleared. Those cancellations never pass through Jabbit, so they do not reach `onCancelled`.

## Constraints

| Constraint              | Android                     | iOS                                                                                                        | Desktop                                                                          | Browser                                                                                                                                  |
|-------------------------|-----------------------------|------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------|
| `requiredNetworkType`   | `androidx.work.NetworkType` | `NWPathMonitor`; `UNMETERED`/`METERED` follow the "expensive" flag, `NOT_ROAMING` behaves like `CONNECTED` | a non-loopback interface that is up; metering is unknown and counts as unmetered | `navigator.onLine`; metering from `navigator.connection` (Chromium), unknown counts as unmetered; `NOT_ROAMING` behaves like `CONNECTED` |
| `requiresCharging`      | system                      | `UIDevice.batteryState`, plus `requiresExternalPower` on the background request                            | `/sys/class/power_supply` on Linux, `pmset` on macOS, unknown elsewhere          | `navigator.getBattery()`, Chromium only                                                                                                  |
| `requiresBatteryNotLow` | system                      | battery level against `JabbitIosOptions.lowBatteryThreshold`                                               | battery level against `JabbitDesktopOptions.lowBatteryThreshold`                 | battery level against `JabbitBrowserOptions.lowBatteryThreshold`, Chromium only                                                          |
| `requiresStorageNotLow` | system                      | free space against `JabbitIosOptions.lowStorageThresholdBytes`                                             | usable space on the volume of `storageDirectory`                                 | remaining quota from `navigator.storage.estimate()`                                                                                      |
| `requiresDeviceIdle`    | system, API 23+             | satisfied only inside a `BGProcessingTask`, which the system schedules while the device is idle            | always satisfied — the desktop exposes no portable idle signal                   | satisfied while the page is hidden, or inside the service worker                                                                         |

Where a platform exposes no API for a constraint, Jabbit treats it as satisfied. Blocking instead
would strand jobs forever on browsers that will never report a battery, or on a desktop that has no
battery at all.

The desktop has no callbacks for any of this, so it is polled every `pollInterval` (30 seconds by
default). That is what decides how quickly a job notices its constraints became satisfiable, and how
often `pmset` is invoked on macOS — set `readPowerSource = false` to never invoke it.

## What each platform guarantees

Neither platform promises a job runs at a given moment; both promise it is not forgotten.

**Android.** Every job is scheduled through `WorkManager` as a single internal worker that resolves
your `JabbitWorker` by name. Payloads must stay under the 10 KB `Data` limit. Retention of finished
jobs, batching, and concurrency are `WorkManager`'s.

**iOS.** The queue lives in `JabbitIosOptions.storage` (`NSUserDefaults` by default). Jobs run
immediately while the app is in the foreground, during the seconds the system grants after the app
is backgrounded, and inside `BGProcessingTask` windows the system hands out — typically at night,
while charging, and only for apps the user opens regularly. A job interrupted mid-run returns to
`ENQUEUED` and is retried. `maxConcurrentJobs` and `finishedJobRetention` from the builder
apply here.

Because iOS schedules opportunistically, treat a periodic job as "at most once per interval,
eventually", never as a timer. `BGTaskScheduler` does not fire in the simulator unless triggered
manually from the debugger.

**Desktop.** Nothing runs while the application is closed: a desktop process is the only thing that
can run its own background work. The queue is persisted, so whatever was pending is picked up on the
next start, and a job interrupted mid-run is retried. Treat the desktop scheduler as "runs while the
application is open, and never forgets what it has not finished".

**Browser.** The queue lives in IndexedDB. While a page is open, jobs behave as they do everywhere
else. When the page is hidden the browser throttles timers to about a minute, so a job may start
late. When every page is closed, only the service worker events above can run anything, and only in
Chromium — so on the web, treat Jabbit as "runs while the app is alive, and picks up where it left
off next time it is opened", with background execution as a Chromium bonus rather than a guarantee.

## Changelog

What changed between releases, and how to move from 1.x, is in [CHANGELOG.md](CHANGELOG.md).

## Demo

`demo/` holds a Compose app for Android, a Compose app for the desktop, and a page for the browser,
all driving the same workers:

```bash
./gradlew :demo:androidApp:installDebug
```

```bash
./gradlew :demo:desktopApp:run
```

```bash
./gradlew :demo:webApp:jsBrowserDevelopmentRun
```

## Building

```bash
./gradlew :jabbit:build
```

Tests run the scheduler against a virtual clock on the JVM and the iOS simulator, against real files
and a real power source on the desktop, and against real IndexedDB, Web Locks and `BroadcastChannel`
in headless Chrome:

```bash
./gradlew :jabbit:testAndroidHostTest :jabbit:jvmTest :jabbit:iosSimulatorArm64Test
```

```bash
export CHROME_BIN="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
./gradlew :jabbit:jsBrowserTest :jabbit:wasmJsBrowserTest
```

## License

MIT License

Copyright (c) 2026 Aleksei Kozlovskiy, Ryadom Tech

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
