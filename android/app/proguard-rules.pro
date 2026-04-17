# Keep data model classes for Gson deserialization
-keep class com.goals.app.data.models.** { *; }
-keep class com.goals.app.widget.WidgetState { *; }
-keep class com.goals.app.widget.WidgetGoal { *; }

# Retrofit
-keepattributes Signature
-keepattributes *Annotation*
-keep class retrofit2.** { *; }
-keep interface retrofit2.** { *; }

# OkHttp
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okhttp3.internal.** { *; }

# Gson
-keep class com.google.gson.** { *; }
