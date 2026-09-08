plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}
android {
    namespace = "com.uroboros"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.uroboros"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "uroboros_debug"
            keyAlias = "uroboros_debug_key"
            keyPassword = "uroboros_debug"
        }
    }
    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    buildFeatures {
        viewBinding = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        // Обычные JVM-тесты компонуются с заглушкой android.jar, где каждый
        // метод бросает исключение. Без этой строки тест, задевший android.util.Log,
        // падает с "not mocked" — а логирование стоит внутри saveEvent, то есть
        // проверить его поведение было бы нечем.
        //
        // ЧЕМ ЭТО ПЛАЧЕНО. Обращение к Android из тестового пути больше не роняет
        // тест: метод-заглушка возвращает 0, null или false и выполнение идёт
        // дальше. Прежде такое падение работало сторожем чистоты слоёв
        // (ARCHITECTURE.md §1): код L1/L3 не должен звать Android вовсе, и тест
        // это ловил побочно. Сторожа больше нет, правило осталось.
        //
        // На подставные объекты тестов настройка не влияет: FakeStickerDao падает
        // на неподготовленном методе по-прежнему, это его собственный код.
        unitTests.isReturnDefaultValues = true
    }
}
dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    implementation(project(":gguf_lib"))
    testImplementation("junit:junit:4.13.2")
}
