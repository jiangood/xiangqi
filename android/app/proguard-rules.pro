-keep class org.opencv.** { *; }
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
