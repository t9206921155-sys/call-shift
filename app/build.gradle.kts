import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// ---------------------------------------------------------------------------
// Подпись релиза. keystore.properties НЕ хранится в репозитории (см. .gitignore).
// Если файла нет — релиз подписывается debug-ключом, чтобы сборка не падала
// на CI/локально (для sideload этого достаточно, но для поставки keystore обязателен).
// ---------------------------------------------------------------------------
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystorePropsFile.exists() && keystoreProps.getProperty("storeFile") != null

android {
    namespace = "fi.callshift.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "fi.callshift.app"
        minSdk = 28          // Android 9.0 — минимум по ТЗ (п. 15.1)
        targetSdk = 35
        versionCode = Integer.parseInt(providers.gradleProperty("versionCode").getOrElse("13"))
        versionName = providers.gradleProperty("versionName").getOrElse("0.4.0-m2")

        resourceConfigurations += listOf("en", "ru")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // ТЗ п. 15.1 (уточнено в v1.1): схема подписи APK v2 ИЛИ v3.
                // ВАЖНО: apksigner при включённой v3 помечает блок v2 как
                // поглощённый, поэтому `apksigner verify` показывает
                // "v2: false, v3: true" — это штатное поведение, а не дефект.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }

    // Вариант для root-устройств (профиль C по ТЗ, п. 3.5 / 10.7)
    flavorDimensions += "capability"
    productFlavors {
        create("standard") { dimension = "capability" }
        create("root") {
            dimension = "capability"
            applicationIdSuffix = ".root"
            versionNameSuffix = "-root"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/*.kotlin_module",
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            "DebugProbesKt.bin",
        )
    }

    lint {
        abortOnError = false
        warningsAsErrors = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    // Нормализация E.164 (ТЗ п. 10.1, FR-3.3)
    implementation("com.googlecode.libphonenumber:libphonenumber:8.13.40")
    // M2: watchdog ролей, retry внешней доставки уведомлений, восстановление MMI
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
