-keep class org.opencv.** { *; }
-keep class ai.onnxruntime.** { *; }
-keep class com.chenlb.mmseg4j.** { *; }
-dontwarn com.chenlb.mmseg4j.**
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
