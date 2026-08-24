package tech.ryadom.jabbit

import android.content.Context
import tech.ryadom.jabbit.internal.JabbitRuntime
import tech.ryadom.jabbit.internal.WorkManagerJabbit

/**
 * Creates the Android implementation of [Jabbit], backed by `androidx.work.WorkManager`.
 *
 * Call this from `Application.onCreate` and keep the result for the lifetime of the process:
 * `WorkManager` starts jobs in a process the user may never have opened, and the workers registered
 * in [configuration] have to be installed before that happens.
 *
 * ```
 * class App : Application() {
 *
 *     lateinit var jabbit: Jabbit
 *         private set
 *
 *     override fun onCreate() {
 *         super.onCreate()
 *         jabbit = createJabbit(
 *             context = this,
 *             configuration = jabbitConfiguration {
 *                 worker(SyncWorker.NAME) { SyncWorker(api) }
 *             }
 *         )
 *     }
 * }
 * ```
 *
 * No manifest entry is required: Jabbit schedules every job as a single worker of its own, and
 * `WorkManager` initializes itself through its own provider.
 */
public fun createJabbit(context: Context, configuration: JabbitConfiguration): Jabbit {
    JabbitRuntime.install(configuration)
    return WorkManagerJabbit(context.applicationContext, configuration)
}
