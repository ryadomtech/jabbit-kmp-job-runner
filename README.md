# Jabbit

Kotlin Multiplatform job runner for Android, iOS and the browser, with an API modelled after
`androidx.work.WorkManager`.

Jobs are declarative: you describe *what* to run, *how often*, and *under which device conditions*,
and the platform decides *when*. On Android that decision is delegated to `WorkManager`. On iOS and
the web, where no equivalent system service exists, Jabbit persists the queue itself and drives it
from the app, using whatever background windows the platform grants.

- One API in `commonMain`, no `expect`/`actual` in your own code beyond obtaining the instance.
- Jobs survive process death, reboots and page reloads.
- Constraints on network, metering, charging, battery, storage and device idle.
- One-time and periodic jobs, unique jobs, tags, retries with exponential or linear backoff,
  input/output payloads, progress, and observable state as `Flow`.

## Installation

```kotlin
// build.gradle.kts of your shared module
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("tech.ryadom:jabbit:1.0.0")
        }
    }
}
```

Targets: `android`, `iosX64`, `iosArm64`, `iosSimulatorArm64`, `js`, `wasmJs`. Minimum versions:
Android API 23, iOS 13, and any browser with IndexedDB.

## Writing a worker

A worker is a suspending function with a name. It is created anew for every run, so keep it
stateless and take its dependencies through the constructor.

```kotlin
class SyncWorker(private val api: Api) : JabbitWorker {

    override suspend fun doWork(job: JobExecution): JobResult {
        val since = job.inputData.getLong("since", 0L)

        return try {
            val synced = api.sync(since)
            job.setProgress(jobDataOf("percent" to 100))
            JobResult.success(jobDataOf("synced" to synced))
        } catch (e: IOException) {
            if (job.runAttemptCount >= 5) JobResult.failure() else JobResult.retry()
        }
    }

    companion object {
        const val NAME = "sync"
    }
}
```

`doWork` is cancelled cooperatively when the platform stops the job — when constraints stop holding,
when the job is cancelled, or when an iOS background window expires. A cancelled job returns to
`ENQUEUED` and runs again later, so check `isActive` inside long loops.

## Creating the scheduler

The configuration is shared; only the factory call is platform specific. Register every worker once,
at startup, in the process the platform will run jobs in.

**Android** — from `Application.onCreate`, because `WorkManager` may start a job in a process the
user never opened:

```kotlin
class App : Application() {

    lateinit var jabbit: Jabbit
        private set

    override fun onCreate() {
        super.onCreate()
        jabbit = createJabbit(
            context = this,
            configuration = jabbitConfiguration {
                worker(SyncWorker.NAME) { SyncWorker(api) }
                worker(CleanupWorker.NAME) { CleanupWorker(database) }
                logger(JabbitLogger.Console)
            }
        )
    }
}
```

No manifest entry is needed.

**iOS** — from `application(_:didFinishLaunchingWithOptions:)`, before it returns, because
`BGTaskScheduler` rejects handlers registered later:

```swift
func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions options: [UIApplication.LaunchOptionsKey: Any]?
) -> Bool {
    let configuration = JabbitConfigurationBuilder()
        .worker(name: "sync") { SyncWorker(api: api) }
        .logger(logger: JabbitLoggerCompanion.shared.Console)
        .build()

    jabbit = JabbitIosKt.createJabbit(
        configuration: configuration,
        options: JabbitIosOptions(
            backgroundTaskIdentifier: "tech.ryadom.example.jabbit",
            storage: UserDefaultsJabbitStorage(),
            lowStorageThresholdBytes: 500 * 1024 * 1024,
            lowBatteryThreshold: 0.15
        )
    )
    return true
}
```

Add to `Info.plist` so the system may wake the app up for pending jobs:

```xml

<key>UIBackgroundModes</key><array>
<string>processing</string>
</array><key>BGTaskSchedulerPermittedIdentifiers</key><array>
<string>tech.ryadom.example.jabbit</string>
</array>
```

**Browser** — as early as the app starts:

```kotlin
val jabbit = createJabbit(
    configuration = jabbitConfiguration {
        worker(SyncWorker.NAME) { SyncWorker(api) }
    },
    options = JabbitBrowserOptions(
        queueName = "example",
        periodicSyncTag = "com.example.refresh"
    )
)
```

Only one tab runs jobs at a time, elected through the Web Locks API; the others forward what they
enqueue to it and mirror its state, so `getJobInfoFlow` reports the same thing in every tab. When
the elected tab closes, another takes over. To keep jobs going after the last tab is closed, add a
service worker — see below.

To use the instance from `commonMain`, pass it in from each platform — usually through the
dependency injection container the app already has.

From Swift, suspending functions arrive as `async` and `kotlin.time.Duration` is not bridgeable, so
reach for the `...Millis` overloads:

```swift
try await jabbit.enqueueUniquePeriodic(
    uniqueName: "sync",
    policy: .keep,
    request: JobRequestKt.periodicJobMillis(
        workerName: "sync",
        repeatIntervalMillis: 6 * 60 * 60 * 1000
    ) { builder in
        builder.setConstraints(
            constraints: ConstraintsKt.constraints { scope in
                scope.requiredNetworkType = .connected
                scope.requiresCharging = true
            }
        )
        builder.setInitialDelayMillis(millis: 30 * 1000)
    }
)
```

## Running jobs after the last tab closes

The browser only lets a closed app do work through a service worker. Build the worker script as its
own Kotlin/JS or Kotlin/Wasm bundle and start Jabbit from it with the **same** configuration and
options the page uses — it has to be able to build the same workers by name, and read the same
queue:

```kotlin
// sw.kt, bundled separately and registered as the service worker
fun main() {
    startJabbitServiceWorker(
        configuration = jabbitConfiguration {
            worker(SyncWorker.NAME) { SyncWorker(api) }
        },
        options = JabbitBrowserOptions(
            queueName = "example",
            periodicSyncTag = "com.example.refresh"
        )
    )
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

`JabbitBrowserOptions.storage` must be reachable from a worker, which rules out
`LocalStorageJabbitStorage`. The default `IndexedDbJabbitStorage` is fine.

## Enqueueing jobs

```kotlin
jabbit.enqueue(
    oneTimeJob(SyncWorker.NAME) {
        setInputData(jobDataOf("since" to lastSyncAt))
        setConstraints(constraints { requiredNetworkType = NetworkType.CONNECTED })
        setInitialDelay(10.seconds)
        setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30.seconds)
        addTag("sync")
    }
)
```

Work that must not pile up gets a unique name:

```kotlin
jabbit.enqueueUnique(
    uniqueName = "sync",
    policy = ExistingJobPolicy.KEEP,
    request = oneTimeJob(SyncWorker.NAME)
)
```

Periodic work is declared the same way. Calling this on every start with `KEEP` is the usual way to
guarantee the job exists exactly once:

```kotlin
jabbit.enqueueUniquePeriodic(
    uniqueName = "cleanup",
    policy = ExistingPeriodicJobPolicy.KEEP,
    request = periodicJob(CleanupWorker.NAME, repeatInterval = 6.hours) {
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
    val running = infos.filter { it.state == JobState.RUNNING }
    val percent = running.firstOrNull()?.progress?.getInt("percent")
}

jabbit.cancelJobsByTag("sync")
```

`JobInfo` carries the state, the tags, the output of the last run, the current progress, the number
of attempts so far, and when the next run is planned.

## Constraints

| Constraint              | Android                     | iOS                                                                                                        | Browser                                                                                                                                  |
|-------------------------|-----------------------------|------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------|
| `requiredNetworkType`   | `androidx.work.NetworkType` | `NWPathMonitor`; `UNMETERED`/`METERED` follow the "expensive" flag, `NOT_ROAMING` behaves like `CONNECTED` | `navigator.onLine`; metering from `navigator.connection` (Chromium), unknown counts as unmetered; `NOT_ROAMING` behaves like `CONNECTED` |
| `requiresCharging`      | system                      | `UIDevice.batteryState`, plus `requiresExternalPower` on the background request                            | `navigator.getBattery()`, Chromium only                                                                                                  |
| `requiresBatteryNotLow` | system                      | battery level against `JabbitIosOptions.lowBatteryThreshold`                                               | battery level against `JabbitBrowserOptions.lowBatteryThreshold`, Chromium only                                                          |
| `requiresStorageNotLow` | system                      | free space against `JabbitIosOptions.lowStorageThresholdBytes`                                             | remaining quota from `navigator.storage.estimate()`                                                                                      |
| `requiresDeviceIdle`    | system, API 23+             | satisfied only inside a `BGProcessingTask`, which the system schedules while the device is idle            | satisfied while the page is hidden, or inside the service worker                                                                         |

Where a browser exposes no API for a constraint, Jabbit treats it as satisfied. Blocking instead
would strand jobs forever on browsers that will never report a battery.

## What each platform guarantees

Neither platform promises a job runs at a given moment; both promise it is not forgotten.

**Android.** Every job is scheduled through `WorkManager` as a single internal worker that resolves
your `JabbitWorker` by name. Payloads must stay under the 10 KB `Data` limit. Retention of finished
jobs, batching, and concurrency are `WorkManager`'s.

**iOS.** The queue lives in `JabbitIosOptions.storage` (`NSUserDefaults` by default). Jobs run
immediately while the app is in the foreground, during the seconds the system grants after the app
is backgrounded, and inside `BGProcessingTask` windows the system hands out — typically at night,
while charging, and only for apps the user opens regularly. A job interrupted mid-run returns to
`ENQUEUED` and is retried. `maxConcurrentJobs` and `finishedJobRetention` from `JabbitConfiguration`
apply here.

Because iOS schedules opportunistically, treat a periodic job as "at most once per interval,
eventually", never as a timer. `BGTaskScheduler` does not fire in the simulator unless triggered
manually from the debugger.

**Browser.** The queue lives in IndexedDB. While a page is open, jobs behave as they do everywhere
else. When the page is hidden the browser throttles timers to about a minute, so a job may start
late. When every page is closed, only the service worker events above can run anything, and only in
Chromium — so on the web, treat Jabbit as "runs while the app is alive, and picks up where it left
off next time it is opened", with background execution as a Chromium bonus rather than a guarantee.

## Demo

`demo/` holds a Compose app for Android and a page for the browser, both driving the same workers:

```bash
./gradlew :demo:androidApp:installDebug
```

```bash
./gradlew :demo:webApp:jsBrowserDevelopmentRun
```

## Building

```bash
./gradlew :jabbit:build
```

Tests run the scheduler against a virtual clock on the JVM and the iOS simulator, and against real
IndexedDB, Web Locks and `BroadcastChannel` in headless Chrome:

```bash
./gradlew :jabbit:testAndroidHostTest :jabbit:iosSimulatorArm64Test
```

```bash
CHROME_BIN="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" ./gradlew :jabbit:jsBrowserTest :jabbit:wasmJsBrowserTest
```
