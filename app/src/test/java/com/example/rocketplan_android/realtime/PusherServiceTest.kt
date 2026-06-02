package com.example.rocketplan_android.realtime

import android.content.Context
import android.net.ConnectivityManager
import com.example.rocketplan_android.logging.LogLevel
import com.example.rocketplan_android.logging.RemoteLogger
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Field

class PusherServiceTest {

    private lateinit var context: Context
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var appVisibilityTracker: AppVisibilityTracker
    private lateinit var remoteLogger: RemoteLogger

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        connectivityManager = mockk(relaxed = true)
        appVisibilityTracker = mockk(relaxed = true)
        remoteLogger = mockk(relaxed = true)

        every { context.applicationContext } returns context
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivityManager
        every { appVisibilityTracker.isAppForeground() } returns true
        mockkStatic(android.net.ConnectivityManager::class)
    }

    @After
    fun tearDown() {
        unmockkStatic(android.net.ConnectivityManager::class)
    }

    private fun createPusherService(): PusherService {
        return PusherService(context, mockk(relaxed = true), remoteLogger, appVisibilityTracker)
    }

    private fun getThrottledErrorTimestamps(service: PusherService): java.util.concurrent.ConcurrentHashMap<String, Long> {
        val field = PusherService::class.java.getDeclaredField("throttledErrorTimestamps")
        field.isAccessible = true
        return field.get(service) as java.util.concurrent.ConcurrentHashMap<String, Long>
    }

    @Test
    fun `cache is bounded at MAX_THROTTLE_CACHE_SIZE when inserting distinct non-error keys`() {
        val service = createPusherService()
        val timestamps = getThrottledErrorTimestamps(service)

        repeat(200) { i ->
            invokeShouldThrottleRemoteLog(service, LogLevel.WARN, "distinct_warning_$i", "code_$i", "exception_$i")
        }

        assertEquals(
            "Cache should be bounded at MAX_THROTTLE_CACHE_SIZE after 200 distinct inserts",
            128,
            timestamps.size
        )
    }

    @Test
    fun `repeated warning inside throttle window is suppressed`() {
        val service = createPusherService()
        val timestamps = getThrottledErrorTimestamps(service)

        val result1 = invokeShouldThrottleRemoteLog(service, LogLevel.WARN, "repeated_warning", "code1", "exception1")
        assertFalse("First occurrence should not be throttled", result1)
        assertEquals("Cache should have one entry", 1, timestamps.size)

        val result2 = invokeShouldThrottleRemoteLog(service, LogLevel.WARN, "repeated_warning", "code1", "exception1")
        assertTrue("Repeat within throttle window should be throttled", result2)
        assertEquals("Cache should still have one entry", 1, timestamps.size)
    }

    @Test
    fun `expired entry is allowed through after throttle window passes`() {
        val service = createPusherService()
        val timestamps = getThrottledErrorTimestamps(service)

        val key = "expired_warning|none|none"
        val oldTimestamp = System.currentTimeMillis() - (16 * 60 * 1000L)
        timestamps[key] = oldTimestamp
        assertEquals("Cache should have one old entry", 1, timestamps.size)

        val result = invokeShouldThrottleRemoteLog(service, LogLevel.WARN, "expired_warning", null, null)
        assertFalse("Expired entry should not be throttled after window passes", result)
        assertEquals("Cache should still have one entry after re-insertion", 1, timestamps.size)
    }

    @Test
    fun `oldest entries are evicted when all entries are fresh and unique`() {
        val service = createPusherService()
        val timestamps = getThrottledErrorTimestamps(service)

        repeat(150) { i ->
            invokeShouldThrottleRemoteLog(service, LogLevel.DEBUG, "fresh_unique_$i", null, null)
            Thread.sleep(1)
        }

        assertEquals(
            "Cache should be bounded at MAX_THROTTLE_CACHE_SIZE with fresh unique entries",
            128,
            timestamps.size
        )

        repeat(22) { i ->
            val key = "fresh_unique_$i|none|none"
            assertFalse("Oldest key fresh_unique_$i should have been evicted", timestamps.containsKey(key))
        }

        repeat(22) { i ->
            val idx = 128 + i
            val key = "fresh_unique_$idx|none|none"
            assertTrue("Newest key fresh_unique_$idx should be present", timestamps.containsKey(key))
        }
    }

    @Test
    fun `ERROR level is never throttled`() {
        val service = createPusherService()

        repeat(10) { i ->
            val result = invokeShouldThrottleRemoteLog(service, LogLevel.ERROR, "error_$i", "code_$i", "exception_$i")
            assertFalse("ERROR level should never be throttled", result)
        }
    }

    private fun invokeShouldThrottleRemoteLog(
        service: PusherService,
        level: LogLevel,
        message: String,
        code: String?,
        exceptionMessage: String?
    ): Boolean {
        val method = PusherService::class.java.getDeclaredMethod(
            "shouldThrottleRemoteLog",
            LogLevel::class.java,
            String::class.java,
            String::class.java,
            String::class.java
        )
        method.isAccessible = true
        return method.invoke(service, level, message, code, exceptionMessage) as Boolean
    }
}