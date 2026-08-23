plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")   // Compose Compiler plugin (Kotlin 2.0+)
    id("com.google.devtools.ksp")               // Room codegen (быстрее kapt)
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.example.myapplication"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.myapplication"
        minSdk = 26          // Android 8.0 — нужен для полноценного VPN/Wi-Fi API
        targetSdk = 35        // Android 15
        versionCode = 1
        versionName = "1.0.0"

        // Под будущий native Xray core (libxray.so). Берём три основных ABI.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        // Передаём порт сервера в BuildConfig, чтобы не хардкодить по коду
        buildConfigField("int", "XRAY_PORT", "10808")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Не тащим дублирующиеся мета-файлы из native-библиотек
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    // ── Core / Lifecycle ──────────────────────────────────────────────
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")   // LifecycleService

    // ── Jetpack Compose (BOM фиксирует версии UI-артефактов) ──────────
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended") // добавочные иконки
    implementation("androidx.compose.animation:animation")

    // ── Навигация между экранами ──────────────────────────────────────
    implementation("androidx.navigation:navigation-compose:2.8.4")

    // ── Room (локальная БД: ключи, лимиты, статистика) ───────────────
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // ── Корутины ─────────────────────────────────────────────────────
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // ── Сериализация (генерация JSON-конфига Xray) ───────────────────
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.google.code.gson:gson:2.11.0")   // fallback для libxray API

    // ── Bouncy Castle (генерация пары ключей X25519 для Reality) ───
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // ── DataStore (настройки: порт, авто-старт, апстрим) ─────────────
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // ── WorkManager (периодическая чистка устаревших сессий) ─────────
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // ── Debug / preview tooling ──────────────────────────────────────
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
