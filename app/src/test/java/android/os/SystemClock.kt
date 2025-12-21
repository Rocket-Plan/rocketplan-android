package android.os

object SystemClock {
    @JvmStatic
    var elapsedRealtimeValue: Long = 0L

    @JvmStatic
    fun elapsedRealtime(): Long = elapsedRealtimeValue
}
