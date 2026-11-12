plugins {
    id("com.android.library")
    id("maven-publish")
}

// Koordinat: com.github.serkandaglioglu.sideaccountads:sideaccountads-admob:<tag> (core ile aynı grup).
group = "com.github.serkandaglioglu.sideaccountads"
version = System.getenv("VERSION") ?: "0.0.0-local"

// AdMob mediation implementasyonu. Uygulama yalnızca bu modülü ekler; core'u o getirir.
// Bir uygulamaya ikinci bir mediator modülü eklenemez (bkz. manifest ve aşağıdaki capability).
android {
    namespace = "com.sideaccountapps.ads.admob"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        aarMetadata {
            minCompileSdk = 36
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
    api(project(":sideaccountads-core"))

    // Legacy GMA SDK. 25.5.0: yedi adaptörün de Google sayfalarında eşlendiği en güncel sürüm.
    implementation("com.google.android.gms:play-services-ads:25.5.0")

    // Google mediation adaptörleri: x.y.z.n -> ağ SDK'sı x.y.z (adaptör kendi SDK'sını getirir).
    // Yükseltirken adaptörlerin POM'undaki play-services-ads sürümünün yukarıdakiyle uyumlu olduğunu kontrol et.
    implementation("com.google.ads.mediation:applovin:13.7.0.0")   // AppLovin SDK 13.7.0
    implementation("com.google.ads.mediation:facebook:6.22.0.1")   // Meta Audience Network 6.22.0
    implementation("com.google.ads.mediation:mintegral:17.2.21.0") // Mintegral 17.2.21
    implementation("com.google.ads.mediation:pangle:8.4.0.5.0")    // Pangle 8.4.0.5
    implementation("com.google.ads.mediation:vungle:7.7.9.0")      // Liftoff Monetize 7.7.9
    implementation("com.google.ads.mediation:fyber:8.5.1.0")       // DT Exchange 8.5.1
    implementation("com.google.ads.mediation:unity:4.21.0.0")
    // Google'ın Unity adaptörü Unity Ads SDK'sını getirmez; ayrıca eklenir.
    implementation("com.unity3d.ads:unity-ads:4.21.0")
}

// Her mediator modülü yayınında bu ortak capability'yi ilan eder. İki mediator aynı projeye girerse (ör. JitPack'in
// tüm modülleri içeren com.github.serkandaglioglu:sideaccountads koordinatıyla) Gradle bağımlılık çözümlemesinde
// "capability conflict" hatasıyla durur. Açık capability verilince varsayılanı da yeniden ilan etmek gerekir.
configurations.matching { it.name.startsWith("releaseVariantRelease") && it.name.endsWith("Publication") }.configureEach {
    outgoing.capability("$group:${project.name}:$version")
    outgoing.capability("com.sideaccountapps.ads:sideaccountads-mediator:1.0")
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
