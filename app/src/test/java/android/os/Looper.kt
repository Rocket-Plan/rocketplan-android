package android.os

class Looper private constructor() {
    companion object {
        private val mainLooper = Looper()

        @JvmStatic
        fun getMainLooper(): Looper = mainLooper

        @JvmStatic
        fun myLooper(): Looper = mainLooper

        @JvmStatic
        fun prepare() = Unit

        @JvmStatic
        fun loop() = Unit
    }
}
