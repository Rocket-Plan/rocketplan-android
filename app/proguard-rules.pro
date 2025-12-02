# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# SLF4J (used by Pusher/java-websocket) - stub out missing classes
-dontwarn org.slf4j.**

# Pusher
-keep class com.pusher.** { *; }
-dontwarn com.pusher.**

# Java WebSocket
-keep class org.java_websocket.** { *; }
-dontwarn org.java_websocket.**

# Retrofit
-keepattributes Signature
-keepattributes Exceptions
-keep class retrofit2.** { *; }
-dontwarn retrofit2.**

# Gson - preserve generic signatures for TypeToken
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keep class * extends com.google.gson.reflect.TypeToken { *; }
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken

# Keep API models
-keep class com.example.rocketplan_android.data.remote.dto.** { *; }
-keep class com.example.rocketplan_android.data.local.entity.** { *; }

# FLIR ThermalSDK - keep all classes (loaded via reflection)
-keep class com.flir.** { *; }
-dontwarn com.flir.**

# Keep Android Log calls for debugging release builds
-keep class android.util.Log { *; }

# OkHttp logging interceptor
-keep class okhttp3.logging.** { *; }
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# Keep app classes for debugging
-keep class com.example.rocketplan_android.** { *; }