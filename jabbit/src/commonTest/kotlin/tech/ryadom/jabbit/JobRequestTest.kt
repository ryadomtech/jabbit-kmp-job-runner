package tech.ryadom.jabbit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class JobRequestTest {

    @Test
    fun coercesBackoffDelayIntoTheSupportedRange() {
        val tooShort = oneTimeJob(OkJob) {
            setBackoffCriteria(BackoffPolicy.LINEAR, 1.seconds)
        }
        val tooLong = oneTimeJob(OkJob) {
            setBackoffCriteria(BackoffPolicy.LINEAR, 10.hours)
        }

        assertEquals(BackoffPolicy.MIN_DELAY, tooShort.backoffDelay)
        assertEquals(BackoffPolicy.MAX_DELAY, tooLong.backoffDelay)
    }

    @Test
    fun coercesPeriodicIntervalAndFlex() {
        val request = periodicJob(OkJob, repeatInterval = 1.minutes) {
            setFlexInterval(1.seconds)
        }

        assertEquals(PeriodicJobRequest.MIN_PERIODIC_INTERVAL, request.repeatInterval)
        assertEquals(PeriodicJobRequest.MIN_FLEX_INTERVAL, request.flexInterval)
    }

    @Test
    fun defaultsFlexToTheWholeInterval() {
        val request = periodicJob(OkJob, repeatInterval = 2.hours)

        assertEquals(2.hours, request.flexInterval)
    }

    @Test
    fun clampsFlexToTheInterval() {
        val request = periodicJob(OkJob, repeatInterval = 30.minutes) {
            setFlexInterval(2.hours)
        }

        assertEquals(30.minutes, request.flexInterval)
    }

    @Test
    fun rejectsInvalidInput() {
        assertFailsWith<IllegalArgumentException> {
            oneTimeJob(OkJob) { setInitialDelay((-1).seconds) }
        }
        assertFailsWith<IllegalArgumentException> { oneTimeJob(OkJob) { addTag(" ") } }
        assertFailsWith<IllegalArgumentException> {
            oneTimeJob(OkJob) { addTag("${RESERVED_TAG_PREFIX}mine") }
        }
    }

    @Test
    fun millisecondOverloadsMatchTheDurationOnes() {
        val fromDuration = periodicJob(OkJob, repeatInterval = 30.minutes) {
            setInitialDelay(45.seconds)
            setFlexInterval(10.minutes)
            setBackoffCriteria(BackoffPolicy.LINEAR, 20.seconds)
        }

        val fromMillis = periodicJobMillis(OkJob, repeatIntervalMillis = 30 * 60 * 1000L) {
            setInitialDelayMillis(45_000)
            setFlexIntervalMillis(10 * 60 * 1000L)
            setBackoffCriteriaMillis(BackoffPolicy.LINEAR, 20_000)
        }

        assertEquals(fromDuration.repeatIntervalMillis, fromMillis.repeatIntervalMillis)
        assertEquals(fromDuration.flexIntervalMillis, fromMillis.flexIntervalMillis)
        assertEquals(fromDuration.initialDelayMillis, fromMillis.initialDelayMillis)
        assertEquals(fromDuration.backoffDelayMillis, fromMillis.backoffDelayMillis)
        assertEquals(30 * 60 * 1000L, fromMillis.repeatIntervalMillis)
    }

    @Test
    fun keepsRetryingUnlessAttemptsAreCapped() {
        assertEquals(JobRequest.UNLIMITED_ATTEMPTS, oneTimeJob(OkJob).maxAttempts)
        assertEquals(4, oneTimeJob(OkJob) { setMaxAttempts(4) }.maxAttempts)
        assertEquals(
            2,
            periodicJob(OkJob, repeatInterval = 1.hours) { setMaxAttempts(2) }.maxAttempts
        )
        assertFailsWith<IllegalArgumentException> { oneTimeJob(OkJob) { setMaxAttempts(0) } }
    }

    @Test
    fun generatesDistinctIdentifiers() {
        val first = oneTimeJob(OkJob)
        val second = oneTimeJob(OkJob)

        assertEquals(36, first.id.value.length)
        assertNotEquals(first.id, second.id)
    }
}
