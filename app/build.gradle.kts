plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI passes its run number so that every published build installs over the previous one.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "io.github.revelationnow.pocketpadpp"
    compileSdk = 34
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "io.github.revelationnow.pocketpadpp"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNumber
        versionName = "0.0.$buildNumber-spike"

        ndk {
            // 64-bit phones and emulators. 32-bit ARM can be added when someone needs it.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    signingConfigs {
        // A throwaway key kept in the repository so that CI builds can update each other.
        // It is public and must never be used for store releases.
        create("dev") {
            storeFile = file("dev.keystore")
            storePassword = "android"
            keyAlias = "pocketpad-dev"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("dev")
        }
        release {
            // Optimised native code matters for judging how the editor feels; shrinking does not yet.
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("dev")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.5"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
