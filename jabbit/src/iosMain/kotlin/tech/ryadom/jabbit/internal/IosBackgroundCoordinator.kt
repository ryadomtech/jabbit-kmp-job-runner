package tech.ryadom.jabbit.internal

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.BackgroundTasks.BGProcessingTaskRequest
import platform.BackgroundTasks.BGTask
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIBackgroundTaskIdentifier
import platform.UIKit.UIBackgroundTaskInvalid
import tech.ryadom.jabbit.JabbitIosOptions
import tech.ryadom.jabbit.JabbitLogger
import tech.ryadom.jabbit.debug
import tech.ryadom.jabbit.warn
import kotlin.concurrent.Volatile

private const val BACKGROUND_TASK_NAME = "tech.ryadom.jabbit"
private const val PLAN_TOLERANCE_MILLIS = 60_000L

internal class IosBackgroundCoordinator(
    private val options: JabbitIosOptions,
    private val logger: JabbitLogger,
    private val scope: CoroutineScope,
    private val deviceState: IosDeviceStateProvider
) {

    @Volatile
    private var runUntilIdle: (suspend () -> Unit)? = null

    @Volatile
    private var wakeUp: (() -> Unit)? = null

    @Volatile
    private var submittedPlan: WakeUpPlan? = null

    @Volatile
    private var pendingPlan: WakeUpPlan? = null

    fun install(runUntilIdle: suspend () -> Unit, wakeUp: () -> Unit) {
        this.runUntilIdle = runUntilIdle
        this.wakeUp = wakeUp
        registerBackgroundTask()
        observeLifecycle()
    }

    fun plan(plan: WakeUpPlan?) {
        pendingPlan = plan
        val submitted = submittedPlan
        val unchanged = plan != null && submitted != null &&
            plan.requiresNetwork == submitted.requiresNetwork &&
            plan.requiresPower == submitted.requiresPower &&
            (plan.atMillis - submitted.atMillis) in
            -PLAN_TOLERANCE_MILLIS..PLAN_TOLERANCE_MILLIS

        if (unchanged || (plan == null && submitted == null)) return
        submit(plan)
    }

    private fun registerBackgroundTask() {
        val identifier = options.backgroundTaskIdentifier ?: return
        val registered = BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(
            identifier = identifier,
            usingQueue = null
        ) { task -> handleBackgroundTask(task) }

        if (registered) {
            logger.debug("Registered background task '$identifier'")
        } else {
            logger.warn(
                "Could not register background task '$identifier'. Add it to " +
                    "BGTaskSchedulerPermittedIdentifiers in Info.plist and register " +
                    "Jabbit before " +
                    "application(_:didFinishLaunchingWithOptions:) returns."
            )
        }
    }

    private fun handleBackgroundTask(task: BGTask?) {
        if (task == null) return
        deviceState.setBackgroundProcessing(true)
        val job = scope.launch { runUntilIdle?.invoke() }
        task.expirationHandler = { job.cancel() }
        job.invokeOnCompletion { cause ->
            deviceState.setBackgroundProcessing(false)
            task.setTaskCompletedWithSuccess(cause == null)
            submit(pendingPlan)
        }
    }

    private fun observeLifecycle() {
        val center = NSNotificationCenter.defaultCenter
        center.addObserverForName(
            name = UIApplicationDidEnterBackgroundNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue
        ) { drainWhileBackgrounded() }

        center.addObserverForName(
            name = UIApplicationWillEnterForegroundNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue
        ) { wakeUp?.invoke() }
    }

    private fun drainWhileBackgrounded() {
        val application = UIApplication.sharedApplication
        var taskIdentifier: UIBackgroundTaskIdentifier = UIBackgroundTaskInvalid
        taskIdentifier = application.beginBackgroundTaskWithName(BACKGROUND_TASK_NAME) {
            application.endBackgroundTask(taskIdentifier)
        }

        scope.launch {
            try {
                runUntilIdle?.invoke()
            } finally {
                withContext(Dispatchers.Main) { application.endBackgroundTask(taskIdentifier) }
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
    private fun submit(plan: WakeUpPlan?) {
        val identifier = options.backgroundTaskIdentifier ?: return
        val scheduler = BGTaskScheduler.sharedScheduler
        scheduler.cancelTaskRequestWithIdentifier(identifier)
        submittedPlan = plan
        if (plan == null) return

        val request = BGProcessingTaskRequest(identifier)
        request.requiresNetworkConnectivity = plan.requiresNetwork
        request.requiresExternalPower = plan.requiresPower
        request.earliestBeginDate = NSDate.dateWithTimeIntervalSince1970(plan.atMillis / 1000.0)

        memScoped {
            val errorPointer = alloc<ObjCObjectVar<NSError?>>()
            if (!scheduler.submitTaskRequest(request, errorPointer.ptr)) {
                submittedPlan = null
                logger.warn(
                    "Could not submit background task request '$identifier': " +
                        errorPointer.value?.localizedDescription
                )
            }
        }
    }
}
