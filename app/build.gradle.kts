plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Seluruh konfigurasi yang dapat diganti dibaca dari gradle.properties (atau -P...).
fun prop(name: String): String =
    providers.gradleProperty(name).orNull ?: error("Property '$name' belum diatur di gradle.properties")

fun flag(name: String): String = prop(name).toBooleanStrict().toString()

// ---- Validasi BASE_URL. Bentuk (https, akhiran "/", tanpa tanda kutip/backslash/spasi) divalidasi SELALU;
// host placeholder (STAGING-HOST / PROD-HOST) hanya boleh di build debug dan DITOLAK untuk assembleRelease/bundleRelease.
val placeholderHost = Regex("(STAGING|PROD)-HOST", RegexOption.IGNORE_CASE)

fun checkBaseUrlShape(name: String, url: String) {
    require(url.startsWith("https://")) { "$name harus diawali https:// (nilai saat ini: '$url')" }
    require(url.endsWith("/")) { "$name harus diakhiri '/' (nilai saat ini: '$url')" }
    require(url.none { it == '"' || it == '\\' || it.isWhitespace() }) { "$name tidak boleh memuat tanda kutip, backslash, atau spasi" }
}

val debugBaseUrl = prop("billing.baseUrl.debug").also { checkBaseUrlShape("billing.baseUrl.debug", it) }
val releaseBaseUrl = prop("billing.baseUrl.release").also { checkBaseUrlShape("billing.baseUrl.release", it) }

android {
    // Namespace = paket kode sumber (tetap). applicationId dapat diganti di gradle.properties.
    namespace = "id.jerco.billingroyal"
    compileSdk = 37

    defaultConfig {
        applicationId = prop("billing.applicationId")
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Feature flag: default FALSE (lihat gradle.properties).
        buildConfigField("boolean", "ENABLE_MARK_PAID", flag("billing.flag.markPaid"))
        buildConfigField("boolean", "ENABLE_CANCEL_INVOICE", flag("billing.flag.cancelInvoice"))
        buildConfigField("boolean", "ENABLE_PRICE_OVERRIDE", flag("billing.flag.priceOverride"))
        buildConfigField("boolean", "ENABLE_DELETE", flag("billing.flag.delete"))
    }

    signingConfigs {
        // Hanya dibuat bila variabel lingkungan tersedia (dipakai oleh GitHub Actions job release).
        val keystorePath = System.getenv("KEYSTORE_PATH")
        if (!keystorePath.isNullOrBlank()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "BASE_URL", "\"$debugBaseUrl\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "BASE_URL", "\"$releaseBaseUrl\"")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        optIn.add("kotlinx.serialization.ExperimentalSerializationApi")
        optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    debugImplementation(libs.okhttp.logging) // logging HTTP hanya untuk build debug (tidak ikut APK release)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.core.splashscreen)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}

// ---- Guard release: gagal bila BASE_URL masih placeholder. Dipasang hanya pada task yang menghasilkan
// artefak release, supaya `./gradlew test` dan `assembleDebug` tetap jalan dengan placeholder.
val verifyReleaseConfig by tasks.registering {
    group = "verification"
    description = "Gagal bila billing.baseUrl.release masih placeholder (PROD-HOST/STAGING-HOST)."
    val url = releaseBaseUrl
    val flagsOn = listOf("markPaid", "cancelInvoice", "priceOverride", "delete")
        .filter { providers.gradleProperty("billing.flag.$it").orNull == "true" }
    doLast {
        require(!placeholderHost.containsMatchIn(url)) {
            "billing.baseUrl.release masih placeholder ('$url'). Isi URL API produksi sebelum membuat build release " +
                "(gradle.properties atau -Pbilling.baseUrl.release=https://host/path/)."
        }
        if (flagsOn.isNotEmpty()) {
            logger.warn("PERINGATAN: feature flag aktif di build release: $flagsOn (default seharusnya false).")
        }
    }
}
tasks.matching { it.name in setOf("assembleRelease", "bundleRelease", "packageRelease", "packageReleaseBundle") }
    .configureEach { dependsOn(verifyReleaseConfig) }
