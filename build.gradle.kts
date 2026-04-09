buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // Work around AGP classpath loading failures on some environments where
        // BuildConfig generation cannot see JavaWriter transitively.
        classpath("com.squareup:javawriter:2.5.0")
    }
}

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
