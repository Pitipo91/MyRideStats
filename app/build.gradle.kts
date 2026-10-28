plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pitipo.tumbada"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pitipo.tumbada"
        // 28 = Android 9: primera versión que entiende la rotación de clave (APK Signature Scheme v3)
        minSdk = 28
        targetSdk = 36
        versionCode = 11
        versionName = "1.8.1"
    }

    // Aquí NO hay ninguna clave de firma.
    // - release: Gradle genera app-release-unsigned.apk y se firma después con scripts/firmar-apk.sh,
    //   que usa la clave guardada FUERA del proyecto y la prueba de rotación (signing-lineage/).
    // - debug: clave debug por defecto de Android, una identidad distinta a la de release.
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// Sin dependencias externas: solo el framework de Android y la librería estándar de Kotlin.
