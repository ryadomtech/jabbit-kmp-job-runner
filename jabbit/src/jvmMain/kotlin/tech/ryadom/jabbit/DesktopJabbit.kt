package tech.ryadom.jabbit

/**
 * The desktop [Jabbit], which owns the resources it needs and can hand them back.
 *
 * Closing it stops the scheduler and releases the single instance lock. A running job is cancelled
 * cooperatively and returns to [JobState.ENQUEUED], so it starts again next time the application
 * runs.
 */
public interface DesktopJabbit :
    Jabbit,
    AutoCloseable
