cat > /workspaces/agent-mobile/gguf_lib/build.gradle.kts << 'EOF'
plugins {
        id("com.android.library")
            id("org.jetbrains.kotlin.android")
}

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
                                                                                                                    abiFilters += listOf("arm64-v8a", "x86_64")
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
    cat > /workspaces/agent-mobile/settings.gradle.kts << 'EOF'
    pluginManagement {
            repositories {
                        google()
                                mavenCentral()
                                        gradlePluginPortal()
            }
    }

    dependencyResolutionManagement {
            repositories {
                        google()
                                mavenCentral()
            }
    }

    rootProject.name = "UroborosMobile"
    include(":app")
    include(":gguf_lib")
    EOF
    
            }
    }
            }
    }                                    cmake {
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
EOF
}
                                    }
                                }
                                        }
                            }
                                            }
                                                                )
                                    }
                        }
                                                                                                    )
                                                                }
                                                }
                    }
}
}