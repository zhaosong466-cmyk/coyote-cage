# MediaPipe / protobuf-lite：反射创建 TaskRunner，保留即可。
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
