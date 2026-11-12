// AGP 9 Kotlin desteğini kendisi getirir ('org.jetbrains.kotlin.android' uygulanmaz).
// Kotlin sürümünü AGP'nin getirdiğinin üstüne sabitlemenin yolu buildscript classpath'idir.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    }
}

plugins {
    id("com.android.application") version "9.3.1" apply false
    id("com.android.library") version "9.3.1" apply false
}
