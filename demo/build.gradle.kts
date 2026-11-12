plugins {
    id("com.android.application")
}

android {
    namespace = "com.sideaccountapps.ads.demo"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.sideaccountapps.ads.demo"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    // Örnekteki testMode = BuildConfig.DEBUG için.
    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Uygulama yalnızca mediator modülünü ekler; core onunla gelir.
    implementation(project(":sideaccountads-admob"))
    implementation("androidx.activity:activity-ktx:1.13.0")
}
