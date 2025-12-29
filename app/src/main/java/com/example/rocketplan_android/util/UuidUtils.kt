package com.example.rocketplan_android.util

import java.security.SecureRandom
import java.util.UUID

/**
 * UUID v7 generator for time-ordered UUIDs.
 *
 * UUID v7 format (RFC 9562):
 * - 48 bits: Unix timestamp in milliseconds
 * - 4 bits: Version (7)
 * - 12 bits: Random
 * - 2 bits: Variant (10)
 * - 62 bits: Random
 *
 * Benefits over UUID v4:
 * - Time-ordered for better database indexing
 * - Monotonically increasing within same millisecond
 * - Still globally unique
 */
object UuidUtils {

    private val random = SecureRandom()
    private var lastTimestamp: Long = 0
    private var counter: Int = 0
    private val lock = Any()

    /**
     * Generates a UUID v7 string.
     *
     * Thread-safe and monotonically increasing within the same millisecond.
     */
    fun generateUuidV7(): String {
        return uuidV7().toString()
    }

    /**
     * Generates a UUID v7.
     *
     * Thread-safe and monotonically increasing within the same millisecond.
     */
    fun uuidV7(): UUID {
        val timestamp: Long
        val randomBits: Long

        synchronized(lock) {
            val now = System.currentTimeMillis()

            if (now == lastTimestamp) {
                // Same millisecond: increment counter for monotonicity
                counter++
                if (counter > 0xFFF) {
                    // Counter overflow: wait for next millisecond
                    Thread.sleep(1)
                    lastTimestamp = System.currentTimeMillis()
                    counter = random.nextInt(0x100)
                }
            } else {
                // New millisecond: reset counter with random start
                lastTimestamp = now
                counter = random.nextInt(0x100)
            }

            timestamp = lastTimestamp
            randomBits = (counter.toLong() and 0xFFF) or (random.nextLong() and 0x0FFF_FFFF_FFFF_F000L)
        }

        // Build UUID v7
        // Most significant bits: timestamp (48 bits) + version (4 bits) + rand_a (12 bits)
        val msb = (timestamp shl 16) or
                (7L shl 12) or  // Version 7
                ((randomBits ushr 52) and 0xFFF)

        // Least significant bits: variant (2 bits) + rand_b (62 bits)
        val lsb = (2L shl 62) or  // Variant 10
                (randomBits and 0x3FFF_FFFF_FFFF_FFFFL)

        return UUID(msb, lsb)
    }

    /**
     * Extracts the timestamp from a UUID v7.
     *
     * @return Unix timestamp in milliseconds, or null if not a valid v7 UUID
     */
    fun extractTimestamp(uuid: UUID): Long? {
        val version = (uuid.mostSignificantBits ushr 12) and 0xF
        if (version != 7L) return null
        return uuid.mostSignificantBits ushr 16
    }

    /**
     * Extracts the timestamp from a UUID v7 string.
     *
     * @return Unix timestamp in milliseconds, or null if not a valid v7 UUID
     */
    fun extractTimestamp(uuidString: String): Long? {
        return try {
            extractTimestamp(UUID.fromString(uuidString))
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
