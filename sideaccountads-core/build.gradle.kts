plugins {
    id("com.android.library")
    id("maven-publish")
}

// JitPack, çok modüllü build'de her modülü com.github.<kullanıcı>.<repo>:<modül>:<tag> olarak sunar.
// groupId açıkça verilir ki mediator modüllerinin POM'u core'u bu koordinatla göstersin.
group = "com.github.serkandaglioglu.sideaccountads"
version = System.getenv("VERSION") ?: "0.0.0-local"

android {
    namespace = "com.sideaccountapps.ads.core"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
        // AGP 9 tüketiciyi varsayılan olarak kütüphanenin compileSdk'sine (37) zorlar;
        // uygulamalar halen compileSdk 36'da.
        aarMetadata {
            minCompileSdk = 36
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // JVM testlerinde android.util.Log gibi çağrılar hata yerine varsayılan değer döner.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Not: 2.11.0 tüketiciyi compileSdk 37 + AGP 9.1'e zorluyor; uygulamalar AGP 8 / compileSdk 36'da.
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")
    implementation("com.google.android.ump:user-messaging-platform:4.0.0")

    testImplementation("junit:junit:4.13.2")
    // android.jar'daki org.json yalnızca stub; JSON testleri gerçek implementasyonla koşar.
    testImplementation("org.json:json:20260814")
}

afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                from(components["release"])
            }
        }
    }
}
