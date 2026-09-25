import java.util.Properties
import java.io.File
import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Production signing is explicit. Never silently sign a release with a disposable key.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystorePropsFile.exists()
if (hasReleaseKeystore) {
    require(listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all {
        !keystoreProps.getProperty(it).isNullOrBlank()
    }) { "Incomplete keystore.properties" }
    require(rootProject.file(keystoreProps.getProperty("storeFile")).isFile) { "Signing keystore not found" }
}
if (providers.gradleProperty("requireReleaseSigning").orNull == "true") {
    require(hasReleaseKeystore) { "Permanent release signing must be configured" }
}

android {
    namespace = "fi.callshift.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "fi.callshift.app"
        minSdk = 28          // Android 9.0 — минимум по ТЗ (п. 15.1)
        targetSdk = 35
        versionCode = Integer.parseInt(providers.gradleProperty("versionCode").getOrElse("26"))
        versionName = providers.gradleProperty("versionName").getOrElse("0.7.2-sms-safety-test")

        resourceConfigurations += listOf("en", "ru")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
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
            buildConfigField("boolean", "STABLE_SIGNING", "false")
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            buildConfigField("boolean", "STABLE_SIGNING", hasReleaseKeystore.toString())
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            } // Without a key CI produces an explicitly unsigned release APK.
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
    implementation("androidx.recyclerview:recyclerview:1.3.2")
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

// Pinned upstream native artifact; never store binaries or credentials in Git.
val prepareTdlib by tasks.registering {
    val destination = layout.buildDirectory.dir("generated/tdlib")
    outputs.dir(destination)
    doLast {
        val archive = layout.buildDirectory.file("downloads/tdlib-1.8.65.tar.gz").get().asFile
        archive.parentFile.mkdirs()
        val expected = "eb777d3e7baedeb02871c691b2090daa1bc51baf9215a81bc55bf54edb76df2b"
        fun digest(file: File): String = MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        if (!archive.exists() || digest(archive) != expected) {
            val connection = URI("https://github.com/up9cloud/android-libtdjson/releases/download/v1.8.65/jniLibs.tar.gz").toURL().openConnection()
            connection.connectTimeout = 30_000
            connection.readTimeout = 180_000
            connection.getInputStream().use { input -> archive.outputStream().use { input.copyTo(it) } }
        }
        check(digest(archive) == expected) { "TDLib checksum mismatch" }
        copy {
            from(tarTree(resources.gzip(archive)))
            into(destination)
        }
        check(destination.get().file("jniLibs/arm64-v8a/libtdjson.so").asFile.exists()) { "Unexpected TDLib archive layout" }
    }
}
android.sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("generated/tdlib/jniLibs"))
tasks.named("preBuild").configure { dependsOn(prepareTdlib) }
