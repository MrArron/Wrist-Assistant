# PebbleKit Android 2 talks to the Pebble app over AIDL/Parcelables.
-keep class io.rebble.pebblekit2.** { *; }
-dontwarn io.rebble.pebblekit2.**
# OkHttp / kotlinx-serialization: only the JSON element API is used (no generated serializers).
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
