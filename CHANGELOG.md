# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project follows
[semantic versioning](https://semver.org/spec/v2.0.0.html).

## [2.0.0] — unreleased

A breaking release. The scheduler is now described once in common code, and payloads are typed.

### Added

- **Desktop target (`jvm`).** The queue is a file under the directory the operating system reserves
  for the application, written atomically. Constraints read the real machine: connectivity through
  `NetworkInterface`, free space through the volume of the queue, and power through
  `/sys/class/power_supply` on Linux or `pmset` on macOS. A single instance lock keeps two processes
  from running the same queue, and the returned `DesktopJabbit` is `AutoCloseable`.
- **`jabbit { }` builder**, callable from `commonMain`, with `android { }`, `ios { }`,
  `desktop { }` and `browser { }` blocks. Only the block matching the current platform is read.
  Nothing has to be passed in from platform code: on Android the application context is picked up
  by `JabbitInitializationProvider` before any application code runs.
- **Typed jobs.** `jobType<I, O>(name)` declares a job; `JabbitWorker<I, O>` implements it;
  `JobExecution<I>.input` hands the payload over already decoded; `JobInfo.output(type)` reads what
  the job produced. Payloads travel through `kotlinx.serialization`.
- `JobProgress(fraction, message)` — what a running job reports, replacing an untyped map.
- `JobRequest.maxAttempts` and `setMaxAttempts(count)`: give up after a number of starts instead of
  counting attempts by hand inside every worker.
- `JobResult.failure(reason)` and `JobInfo.failureReason`.
- `requestPersistentStorage()` on the web, asking the browser not to evict the queue, plus
  `browser { requestPersistentStorage = true }` to ask on startup.
- `JabbitListener`, installed with `jabbit { listener(...) }`: `onEnqueued`, `onStarted`,
  `onSucceeded`, `onFailed`, `onRetryScheduled`, `onStopped` and `onCancelled`. Every method has an
  empty body, a listener that throws is logged and skipped, and `onEnqueued` is guaranteed to
  precede `onStarted`.
- Jitter of up to a fifth on top of every retry delay, so jobs that failed together come back
  spread out.
- API documentation generated with Dokka and published to GitHub Pages on every push to `main`.
- Behavioural tests for Android against a real `WorkManager` (Robolectric and `work-testing`),
  binary compatibility validation of the published API, a ktlint gate in CI, and an
  `.editorconfig`.

### Changed

- `ExistingJobPolicy.REPLACE` and `ExistingPeriodicJobPolicy.CANCEL_AND_REENQUEUE` now **drop** the
  replaced job on every platform. Its identifier stops resolving instead of turning up later as
  `CANCELLED`. Android behaved this way already; the other platforms now match it.
- `JobInfo.workerName` is now `JobInfo.typeName`.
- Progress lives in memory only. On Android it is written to `WorkManager` at most once every
  500 ms, always the latest value.
- The published license is MIT, matching `LICENSE`. Version 1.0.0 was published declaring
  Apache 2.0 by mistake.
- The JVM artifacts, both desktop and Android, are compiled against Java 11 instead of Java 21.
  Java 21 class files kept out any application on an Android Gradle Plugin older than 8.2, and
  any desktop runtime below 21.

### Removed

- The platform `createJabbit(...)` functions, along with `JabbitConfiguration`, its builder and
  `jabbitConfiguration { }`. Use `jabbit { }`.
- `JobData`, `JobDataBuilder`, `jobDataOf()` and `jobData { }`. Use `@Serializable` payloads.
- `JabbitWorkerFactory`. Registering a worker with a lambda covers dependency injection:
  `worker(SyncJob) { container.get() }`.
- `JabbitIosOptions`, `JabbitDesktopOptions`, `JabbitBrowserOptions` and `defaultStorageDirectory`,
  replaced by the platform blocks of the builder.

### Fixed

- `pmset` could hang a thread forever: the timeout was applied after a blocking read of a process
  that may never exit.
- Two concurrent reads of the machine state could spawn `pmset` twice.
- iOS state shared between the main queue and background task threads is now `@Volatile`.
- An Android job failed silently when Jabbit had never been created in that process; it now says so
  in the log.
- Progress was persisted on every update, costing IO for a value that is dropped when the job ends.

### Migrating from 1.0.0

| 1.0.0                                                     | 2.0.0                                           |
|-----------------------------------------------------------|-------------------------------------------------|
| `createJabbit(context, configuration)`                    | `jabbit { android { } }`                        |
| `createJabbit(configuration, JabbitIosOptions(...))`      | `jabbit { ios { ... } }`                        |
| `createJabbit(configuration, JabbitBrowserOptions(...))`  | `jabbit { browser { ... } }`                    |
| `startJabbitServiceWorker(configuration, options)`        | `startJabbitServiceWorker { ... }`              |
| `jabbitConfiguration { worker("sync") { SyncWorker() } }` | `jabbit { worker(SyncJob) { SyncWorker() } }`   |
| `JabbitWorker { job -> ... }`                             | `JabbitWorker<I, O> { job -> ... }`             |
| `job.inputData.getLong("since")`                          | `job.input.since`                               |
| `JobResult.success(jobDataOf("items" to 7))`              | `JobResult.success(SyncOutput(items = 7))`      |
| `JobResult.failure(jobDataOf("reason" to "..."))`         | `JobResult.failure("...")`                      |
| `info.outputData.getInt("items")`                         | `info.output(SyncJob)?.items`                   |
| `info.workerName`                                         | `info.typeName`                                 |
| `job.setProgress(jobDataOf("percent" to 50))`             | `job.setProgress(JobProgress(fraction = 0.5f))` |
| `info.progress.getInt("percent")`                         | `info.progress?.fraction`                       |
| `if (job.runAttemptCount >= 5) failure() else retry()`    | `setMaxAttempts(5)` on the request              |

The module declaring job payloads needs the `kotlin("plugin.serialization")` plugin.

## [1.0.0] — 2026-08-24

First release: Android on `WorkManager`, iOS on a queue Jabbit owns itself and `BGTaskScheduler`,
and the browser on IndexedDB with tab coordination through Web Locks and an optional service
worker. One-time and periodic jobs, unique jobs, tags, constraints, retries with backoff,
input/output payloads, progress and observable state as `Flow`.
