import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import dev.amatv.build.MusicKitSongIdVisitorFactory
import dev.amatv.build.MusicKitStackMapFrames
import dev.amatv.build.VerifyMusicKitSdkTask
import java.util.Properties

val localProperties = Properties().apply {
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.exists()) propertiesFile.inputStream().use(::load)
}

fun localSetting(name: String, fallback: String = ""): String =
    providers.gradleProperty(name).orNull
        ?: localProperties.getProperty(name)
        ?: System.getenv(name)
        ?: fallback

fun quotedBuildConfig(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val bundledDeveloperToken = localSetting("AM_DEVELOPER_TOKEN_FILE").takeIf(String::isNotBlank)
    ?.let { file(it).readText().trim() }
    ?: localSetting("AM_DEVELOPER_TOKEN")

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.amatv"
    compileSdk = 36
    testBuildType = "flymeGecko"

    defaultConfig {
        applicationId = "dev.amatv"
        minSdk = 28
        targetSdk = 36
        versionCode = 15
        versionName = "0.6.2"
        buildConfigField("boolean", "FLYME_AUTO", "false")
        buildConfigField("boolean", "FLYME_NATIVE_MEDIA", "false")
        buildConfigField("boolean", "FLYME_UI_PREVIEW", "false")

        buildConfigField("String", "AUTH_BROWSER_ACTIVITY", "\"dev.amatv.integration.MusicKitWebAuthActivity\"")
        buildConfigField("boolean", "AUTH_BROWSER_IS_GECKO", "false")
        buildConfigField("String", "STATION_ENGINE_CLASS", "\"\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "String",
            "MUSICKIT_DEVELOPER_TOKEN",
            quotedBuildConfig(bundledDeveloperToken),
        )
        buildConfigField(
            "String",
            "MUSICKIT_TOKEN_ENDPOINT",
            quotedBuildConfig(localSetting("AM_TOKEN_ENDPOINT")),
        )
        buildConfigField(
            "String",
            "APPLE_MUSIC_STOREFRONT",
            quotedBuildConfig(localSetting("AM_STOREFRONT", "tw")),
        )
    }

    buildTypes {
        create("flymeGecko") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("debug")
            val fixedKeystorePath = localSetting("FLYME_SIGNING_KEYSTORE_FILE")
            if (fixedKeystorePath.isNotBlank()) {
                val fixedKeystore = rootProject.file(fixedKeystorePath)
                require(fixedKeystore.isFile) { "Flyme signing keystore file is missing" }
                signingConfig = signingConfigs.create("flymeFixed") {
                    storeFile = fixedKeystore
                    storePassword = "android"
                    keyAlias = "androiddebugkey"
                    keyPassword = "android"
                }
            }
            applicationIdSuffix = ".flyme"
            versionNameSuffix = "-flyme.15.0-rc2.6"
            manifestPlaceholders["flymeApplicationLabel"] = "@string/flyme_app_name"
            manifestPlaceholders["flymeRadioProbeEnabled"] = "false"
            matchingFallbacks += "release"
            proguardFile("src/flymeGecko/proguard-rules.pro")
            buildConfigField("String", "AUTH_BROWSER_ACTIVITY", "\"dev.amatv.integration.GeckoMusicKitAuthActivity\"")
            buildConfigField("boolean", "AUTH_BROWSER_IS_GECKO", "true")
            buildConfigField("boolean", "FLYME_AUTO", "true")
            buildConfigField("boolean", "FLYME_NATIVE_MEDIA", localSetting("flyme.nativeMedia", "true"))
            // Stations play through MusicKit JS in GeckoView; songs stay on the native SDK.
            buildConfigField("String", "STATION_ENGINE_CLASS",
                if (localSetting("flyme.geckoStations", "true").toBoolean()) "\"dev.amatv.integration.GeckoStationEngine\"" else "\"\"")
            // Keep the configurable token endpoint in this variant. SDK-free
            // builds can exercise the UI; real playback still needs MusicKit.
            buildConfigField("String", "MUSICKIT_TOKEN_ENDPOINT", quotedBuildConfig(localSetting("AM_TOKEN_ENDPOINT")))
            ndk.abiFilters.clear()
            ndk.abiFilters += "arm64-v8a"
            if (localSetting("flyme.uiVerification", "false").toBoolean()) {
                // Instrumentation on the existing x86_64 Android 11 emulator.
                // -Pflyme.previewRelease=true: minified and non-debuggable like the release APK,
                // for performance measurements (a debuggable build runs far slower).
                isMinifyEnabled = localSetting("flyme.previewRelease", "false").toBoolean()
                isShrinkResources = isMinifyEnabled
                isDebuggable = !isMinifyEnabled
                applicationIdSuffix = ".flyme.preview"
                versionNameSuffix = "-flyme-preview"
                manifestPlaceholders["flymeApplicationLabel"] = "@string/flyme_preview_name"
                manifestPlaceholders["flymeRadioProbeEnabled"] = "true"
                buildConfigField("boolean", "FLYME_UI_PREVIEW", "true")
                val previewAbi = localSetting("flyme.previewAbi", "x86_64")
                require(previewAbi in listOf("arm64-v8a", "x86_64")) { "Unsupported preview ABI" }
                ndk.abiFilters.clear()
                ndk.abiFilters += previewAbi
            }
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

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

// Preserve 64-bit Apple catalog song IDs in the legacy SDK's JSON metadata parser.
// The downloaded official AAR is validated and stays unmodified on disk.
val verifyMusicKitSdkCompatibility = tasks.register<VerifyMusicKitSdkTask>("verifyMusicKitSdkCompatibility") {
    sdkAars.from(fileTree("libs") { include("mediaplayback*.aar") })
}
tasks.named("preBuild").configure { dependsOn(verifyMusicKitSdkCompatibility) }

val musicKitFrameSdkAars = fileTree("libs") { include("mediaplayback*.aar", "musickitauth*.aar") }
val musicKitFrameRepairClasses = providers.provider {
    MusicKitStackMapFrames.missingFrameClasses(musicKitFrameSdkAars.files)
}

androidComponents {
    beforeVariants(selector().all()) { variant ->
        variant.enable = variant.buildType == "flymeGecko"
    }
    onVariants(selector().withBuildType("flymeGecko")) { variant ->
        variant.outputs.forEach { it.versionCode.set(60) }
        // Direct APK distribution benefits from compressed browser binaries.
        // Android extracts the selected architecture once during installation.
        variant.packaging.jniLibs.useLegacyPackaging.set(true)
        variant.instrumentation.transformClassesWith(
            MusicKitSongIdVisitorFactory::class.java,
            InstrumentationScope.ALL,
        ) { parameters ->
            parameters.frameRepairClasses.set(musicKitFrameRepairClasses)
        }
        variant.instrumentation.setAsmFramesComputationMode(
            FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_CLASSES,
        )
    }
}

val verifyFlymeRuntime = tasks.register("verifyFlymeRuntime") {
    doLast {
        if (!localSetting("flyme.uiVerification", "false").toBoolean()) {
            val fixedKeystorePath = localSetting("FLYME_SIGNING_KEYSTORE_FILE")
            check(fixedKeystorePath.isNotBlank() && rootProject.file(fixedKeystorePath).isFile) {
                "Vehicle builds require FLYME_SIGNING_KEYSTORE_FILE; refusing a generated debug key"
            }
            check(fileTree("libs") { include("mediaplayback*.aar") }.files.isNotEmpty() &&
                fileTree("libs") { include("musickitauth*.aar") }.files.isNotEmpty()) {
                "Flyme playback builds require the official MusicKit playback and authentication AARs in app/libs. See docs/flyme-auto.md."
            }
            check(bundledDeveloperToken.isNotBlank() || localSetting("AM_TOKEN_ENDPOINT").isNotBlank()) {
                "Configure AM_DEVELOPER_TOKEN_FILE or AM_TOKEN_ENDPOINT before building the Flyme playback APK."
            }
        }
    }
}
tasks.matching { it.name == "preFlymeGeckoBuild" }.configureEach { dependsOn(verifyFlymeRuntime) }

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.0")
    implementation("androidx.media3:media3-ui:1.11.0")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    implementation("io.github.kyant0:backdrop:1.0.6")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.4.0")

    add("flymeGeckoImplementation", "org.mozilla.geckoview:geckoview:153.0.20260810162159") {
        exclude(group = "androidx.media3")
    }
}
