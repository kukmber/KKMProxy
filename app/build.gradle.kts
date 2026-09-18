import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties
import java.util.zip.GZIPInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val abis = setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")

// Ядро VPN — mihomo (как в FlClashX). Бинарник кладётся в APK как libmihomo.so
// и запускается отдельным процессом из nativeLibraryDir.
val mihomoVersion = "v1.19.31"
val mihomoAbis = mapOf(
    "arm64-v8a" to "arm64-v8",
    "armeabi-v7a" to "armv7",
    "x86" to "386",
    "x86_64" to "amd64",
)
val mihomoDir = layout.projectDirectory.dir("mihomo-bin/$mihomoVersion")

// Для нового релиза достаточно поменять эту строку и поставить тег такой же версии
val appVersionName = "1.0.1"

// Номер сборки считается из версии: 1.0.1 -> 10001. Он должен только расти,
// иначе Android не даст поставить обновление поверх установленного.
val appVersionCode = appVersionName.split('.').map { it.toIntOrNull() ?: 0 }
    .let { (it.getOrElse(0) { 0 } * 10000) + (it.getOrElse(1) { 0 } * 100) + it.getOrElse(2) { 0 } }

// Версия оригинального tg-ws-proxy, с которого сделан Kotlin-порт TgWsProxy
val tgwsPortedFrom = "v1.10.2"

// Версия ядра ByeDPI берётся прямо из исходников подмодуля
fun byedpiVersion(): String = file("src/main/cpp/byedpi/main.c")
    .takeIf { it.exists() }
    ?.readLines()
    ?.firstNotNullOfOrNull { Regex("""#define\s+VERSION\s+"([^"]+)"""").find(it)?.groupValues?.get(1) }
    ?: "unknown"

// Готовые файлы называются KKMProxy-1.0.0-arm64-v8a-debug.apk и т.п.
base {
    archivesName.set("KKMProxy-$appVersionName")
}

// Ключ подписи: локально — keystore.properties, на CI — переменные окружения
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(key: String, env: String): String? =
    keystoreProps.getProperty(key) ?: System.getenv(env)

android {
    namespace = "io.github.romanvht.byedpi"
    //noinspection GradleDependency
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.romanvht.byedpi"
        minSdk = 23
        //noinspection OldTargetApi
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.addAll(abis)
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    // Версии встроенных компонентов — показываются на экране обновлений
    defaultConfig {
        buildConfigField("String", "MIHOMO_VERSION", "\"$mihomoVersion\"")
        buildConfigField("String", "BYEDPI_VERSION", "\"v${byedpiVersion()}\"")
        buildConfigField("String", "TGWS_VERSION", "\"$tgwsPortedFrom\"")
    }

    signingConfigs {
        create("release") {
            val store = signingValue("storeFile", "KEYSTORE_FILE")
            if (store != null && file(store).exists()) {
                storeFile = file(store)
                storePassword = signingValue("storePassword", "KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Без ключа релиз соберётся неподписанным, а не упадёт
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
            buildConfigField("String", "VERSION_NAME",  "\"${defaultConfig.versionName}\"")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Основной вес APK — ядро mihomo, его сжатие кода не трогает;
            // зато R8 ломает разбор YAML/JSON по отражению и требует много памяти
            isMinifyEnabled = false
            isShrinkResources = false
        }
        debug {
            buildConfigField("String", "VERSION_NAME",  "\"${defaultConfig.versionName}-debug\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8)
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // https://android.izzysoft.de/articles/named/iod-scan-apkchecks?lang=en#blobs
    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(mihomoDir)
        }
    }

    lint {
        // Полный анализ съедает много памяти и для выпуска сборки не нужен
        checkReleaseBuilds = false
    }

    packaging {
        jniLibs {
            // Исполняемый libmihomo.so должен лежать на диске
            useLegacyPackaging = true
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include(*abis.toTypedArray())
            isUniversalApk = true
        }
    }
}

dependencies {
    //noinspection GradleDependency
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-service:2.9.4")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.yaml:snakeyaml:2.2")
    // QR-сканер; core 3.3.0 — чтобы не требовался minSdk 24
    implementation("com.journeyapps:zxing-android-embedded:4.3.0") { isTransitive = false }
    implementation("com.google.zxing:core:3.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}

tasks.register<Exec>("runNdkBuild") {
    group = "build"

    val ndkDir = android.ndkDirectory
    executable = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        "$ndkDir\\ndk-build.cmd"
    } else {
        "$ndkDir/ndk-build"
    }
    setArgs(listOf(
        "NDK_PROJECT_PATH=build/intermediates/ndkBuild",
        "NDK_LIBS_OUT=src/main/jniLibs",
        "APP_BUILD_SCRIPT=src/main/jni/Android.mk",
        "NDK_APPLICATION_MK=src/main/jni/Application.mk"
    ))

    println("Command: $commandLine")
}

val downloadMihomo by tasks.registering {
    group = "build"
    description = "Скачивает mihomo $mihomoVersion для всех ABI"
    val targetDir = mihomoDir.asFile
    outputs.dir(targetDir)
    doLast {
        mihomoAbis.forEach { (abi, arch) ->
            val target = File(targetDir, "$abi/libmihomo.so")
            if (target.exists() && target.length() > 0) return@forEach
            target.parentFile.mkdirs()
            val url = "https://github.com/MetaCubeX/mihomo/releases/download/$mihomoVersion/" +
                "mihomo-android-$arch-$mihomoVersion.gz"
            logger.lifecycle("Downloading $url")
            val tmp = File(target.path + ".part")
            GZIPInputStream(uri(url).toURL().openStream()).use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            tmp.renameTo(target)
        }
    }
}

tasks.preBuild {
    dependsOn("runNdkBuild")
    dependsOn(downloadMihomo)
}
