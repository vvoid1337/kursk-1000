plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.kursk1000"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.kursk1000"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Адрес бекенда вынесен из исходников в BuildConfig: сменить машину/сеть
        // музея теперь можно конфигурацией сборки, а не правкой кода и перекомпиляцией.
        // Сейчас один LAN-IP на все типы сборки (поведение прежнее). Когда появится
        // реальный сервер — переопределить BASE_URL в блоке release ниже.
        buildConfigField("String", "BASE_URL", "\"http://192.168.0.163:8000\"")
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            // Заготовка правил R8 на будущее (минификация пока выключена выше). Когда
            // включим shrinking — сюда лягут keep-правила для Media3/Coil.
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // ProcessLifecycleOwner: сканируем по жизненному циклу всего приложения, а не
    // Activity — иначе поворот экрана (stop→start Activity) перезапускал бы скан.
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.accompanist.permissions)
    // Корутины объявлены явно, а не транзитивно через lifecycle — единый источник версии.
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Мультимедиа карточки: изображения (Coil 3) и встроенный видеоплеер с дисковым
    // кешем (Media3 ExoPlayer + PlayerView + datasource/database для CacheDataSource).
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.database)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}