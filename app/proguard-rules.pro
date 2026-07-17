# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep CWA API response data classes
-keep class com.example.weathertool.WeatherResponse { *; }
-keep class com.example.weathertool.WeatherRecords { *; }
-keep class com.example.weathertool.LocationData { *; }
-keep class com.example.weathertool.WeatherElement { *; }
-keep class com.example.weathertool.TimeData { *; }
-keep class com.example.weathertool.Parameter { *; }

# Retrofit
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepattributes Signature
-keepattributes Exceptions

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Gson
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
