# Module Jabbit

Kotlin Multiplatform job runner for Android, iOS, the desktop and the browser, with an API like in `androidx.work.WorkManager`.

Describe a job with `jobType`, implement it as a `JabbitWorker`, and build the scheduler once in
common code with `jabbit { }`:

```kotlin
val SyncJob = jobType<SyncInput, SyncOutput>("sync")

val jabbit = jabbit {
    worker(SyncJob) { SyncWorker(api) }

    ios { backgroundTaskIdentifier = "com.example.app.jabbit" }
    desktop { applicationName = "Example" }
}

jabbit.enqueue(oneTimeJob(SyncJob, SyncInput(since = lastSync)))
```

On Android jobs are handed to `WorkManager`. Everywhere else Jabbit owns the queue itself and
drives it from the app, using whatever background windows the platform grants — `BGTaskScheduler`
on iOS, Background Sync in the browser, and the lifetime of the process on the desktop.

Declarations are tagged with the platforms they are available on, so anything without a tag can be
used from common code.

# Package tech.ryadom.jabbit

Everything an application touches: the `jabbit { }` builder, job types and requests, workers and
their results, and the `Jabbit` instance that schedules and reports on them.
