-keep class org.opencv.** { *; }
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# cn.hutool optional transitive deps
-dontwarn com.github.houbb.**
-dontwarn com.github.promeg.**
-dontwarn com.github.stuxuhai.**
-dontwarn com.googlecode.aviator.**
-dontwarn com.hankcs.**
-dontwarn com.huaban.**
-dontwarn com.jfirer.**
-dontwarn com.mayabot.**
-dontwarn com.ql.util.**
-dontwarn com.rnkrsoft.**
-dontwarn io.github.logtube.**
-dontwarn net.sourceforge.pinyin4j.**
-dontwarn org.ansj.**
-dontwarn org.apache.commons.jexl3.**
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.apache.lucene.**
-dontwarn org.apdplat.**
-dontwarn org.jboss.logging.**
-dontwarn org.lionsoul.**
-dontwarn org.mozilla.javascript.**
-dontwarn org.mvel2.**
-dontwarn org.pmw.tinylog.**
-dontwarn org.slf4j.**
-dontwarn org.springframework.expression.**
-dontwarn org.tinylog.**
-dontwarn com.chenlb.mmseg4j.**
