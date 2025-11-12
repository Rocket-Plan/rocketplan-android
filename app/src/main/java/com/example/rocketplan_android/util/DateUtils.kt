package com.example.rocketplan_android.util

import java.text.SimpleDateFormat
import java.time.ZoneOffset
import java.time.format.DateTimeFormatterBuilder
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object DateUtils {

    private val formats = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd"
    )
    private val apiOutputFormatter = DateTimeFormatterBuilder()
        .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
        .appendOffset("+HH:MM", "+00:00")
        .toFormatter(Locale.US)
    private val httpDateFormatter = ThreadLocal.withInitial {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }
    }

    fun parseApiDate(value: String?): Date? {
        if (value.isNullOrBlank()) return null
        formats.forEach { pattern ->
            val formatter = SimpleDateFormat(pattern, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            runCatching { formatter.parse(value) }?.getOrNull()?.let { return it }
        }
        return null
    }

    fun formatApiDate(value: Date): String =
        apiOutputFormatter.format(value.toInstant().atOffset(ZoneOffset.UTC))

    fun parseHttpDate(value: String?): Date? {
        if (value.isNullOrBlank()) return null
        return runCatching { httpDateFormatter.get().parse(value) }.getOrNull()
    }
}
