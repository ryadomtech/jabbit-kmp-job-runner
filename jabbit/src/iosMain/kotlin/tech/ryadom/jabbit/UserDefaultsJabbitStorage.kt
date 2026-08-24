package tech.ryadom.jabbit

import platform.Foundation.NSUserDefaults

/**
 * [JabbitStorage] backed by `NSUserDefaults`.
 *
 * Pass a suite name to share the queue with an app extension:
 * `UserDefaultsJabbitStorage(defaults = NSUserDefaults(suiteName = "group.com.example.app"))`.
 */
public class UserDefaultsJabbitStorage(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
    private val key: String = DEFAULT_KEY
) : JabbitStorage {

    override suspend fun read(): String? = defaults.stringForKey(key)

    override suspend fun write(value: String) {
        defaults.setObject(value, key)
    }

    private companion object {

        const val DEFAULT_KEY = "tech.ryadom.jabbit.jobs"
    }
}
