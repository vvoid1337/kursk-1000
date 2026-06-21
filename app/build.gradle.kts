plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
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

        // Адрес бекенда вынесен из исходников в BuildConfig: сменить машину/сеть
        // музея теперь можно конфигурацией сборки, а не правкой кода и перекомпиляцией.
        // Сейчас один LAN-IP на все типы сборки (поведение прежнее). Когда появится
        // реальный сервер — переопределить BASE_URL в блоке release ниже.
        buildConfigField("String", "BASE_URL", "\"http://192.168.0.163:8000\"")
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
            signingConfig = signingConfigs.getByName("debug")
            // R8 включён; proguard-rules.pro оставлен для точечных keep-правил,
            // если ручная release-проверка найдёт проблемы в Media3/Coil.
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
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // ui.tooling — не тест: питает @Preview-функции Compose в debug-сборке.
    debugImplementation(libs.androidx.compose.ui.tooling)
}
