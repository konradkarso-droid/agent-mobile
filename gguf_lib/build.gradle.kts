plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// Правка 2026-08-26: ускоритель повторной сборки C++ (ccache).
//
// Зачем. Нативная часть этого модуля — это llama.cpp целиком, и на чистой
// машине CI она собирается с нуля каждый прогон. Это львиная доля из
// шестнадцати минут сборки. ccache подставляется перед компилятором и отдаёт
// готовый объектный файл, если такой же исходник с такими же флагами уже
// собирался: он сравнивает СОДЕРЖИМОЕ, а не даты изменения файлов, и поэтому
// переживает выкачку исходников заново (у свежескачанных файлов даты всегда
// новые, и обычный кэш объектных файлов на этом ломается).
//
// Почему через переменную окружения, а не всегда. Это НЕОБЯЗАТЕЛЬНЫЙ ускоритель:
// если ccache в системе нет, CMake упал бы на попытке его запустить. Поэтому
// он включается только там, где заведомо установлен — то есть на CI, где
// рабочий процесс выставляет USE_CCACHE=1. Везде остальное собирается ровно
// как раньше. Ничего, кроме скорости, он не меняет: на выходе те же объектные
// файлы.
//
// Как проверить, что работает: шаг "Статистика ccache" в конце прогона CI
// печатает долю попаданий. На первом прогоне после этой правки она будет
// нулевой — кэшу неоткуда взяться, он в этом прогоне только наполняется.
// Начиная со второго доля должна быть высокой.
val useCcache = System.getenv("USE_CCACHE") == "1"

android {
    namespace = "com.dark.gguf_lib"
    compileSdk = 34

    ndkVersion = "27.3.13750724"

    defaultConfig {
        minSdk = 29

        consumerProguardFiles("consumer-rules.pro")

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_TOOLS=OFF",
                    "-DLLAMA_BUILD_SERVER=OFF",
                    "-Wno-deprecated",
                    "-Wno-dev",
                )
                if (useCcache) {
                    arguments += listOf(
                        "-DCMAKE_C_COMPILER_LAUNCHER=ccache",
                        "-DCMAKE_CXX_COMPILER_LAUNCHER=ccache",
                    )
                }
                // Правка 2026-08-24: убран "x86_64" — нужен только эмулятору,
                // на ARM-устройстве это мёртвый вес в APK (вторая копия движка).
                // Вернуть, если понадобится запуск в Android-эмуляторе на x86-хосте.
                abiFilters += listOf("arm64-v8a")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
                "consumer-rules.pro",
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.31.4"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    implementation("androidx.core:core-ktx:1.12.0")
}
