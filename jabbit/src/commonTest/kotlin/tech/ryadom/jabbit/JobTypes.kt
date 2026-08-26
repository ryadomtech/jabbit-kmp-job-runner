package tech.ryadom.jabbit

import kotlinx.serialization.Serializable

@Serializable
internal data class Greeting(val name: String)

internal val OkJob = jobType<Unit, Int>("ok")

internal val GreetJob = jobType<Greeting, String>("greet")

internal val FlakyJob = jobType<Unit, Unit>("flaky")

internal val FailingJob = jobType<Unit, Unit>("failing")

internal val ThrowingJob = jobType<Unit, Unit>("throwing")

internal val SlowJob = jobType<Unit, Unit>("slow")

internal val UploadJob = jobType<Unit, Unit>("upload")

internal val BeatJob = jobType<Unit, Unit>("beat")

internal val ReportingJob = jobType<Unit, Unit>("reporting")

internal val NeverJob = jobType<Unit, Unit>("never")

internal val SyncJob = jobType<Greeting, Unit>("sync")
