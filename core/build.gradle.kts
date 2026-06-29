plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.jiangood.xq.core"
    compileSdk = 34

    defaultConfig {
        minSdk = 34
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.hutool)
    api(libs.opencv.android)
}
