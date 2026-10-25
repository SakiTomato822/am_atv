import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationScope
import dev.amatv.build.MusicKitSongIdVisitorFactory
import dev.amatv.build.VerifyMusicKitSdkTask
import java.util.Properties
import java.util.Base64
import java.time.Instant

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

    defaultConfig {
        applicationId = "dev.amatv"
        minSdk = 28
        targetSdk = 36
        versionCode = 13
        versionName = "0.6.0"

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
        buildConfigField(
            "String",
            "YTM_OAUTH_CLIENT_ID",
            quotedBuildConfig(localSetting("YTM_OAUTH_CLIENT_ID")),
        )
        buildConfigField(
            "String",
            "YTM_OAUTH_CLIENT_SECRET",
            quotedBuildConfig(localSetting("YTM_OAUTH_CLIENT_SECRET")),
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        create("standalone") {
            initWith(getByName("release"))
            // Same local signing identity lets the TV update without losing its account.
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            buildConfigField("String", "MUSICKIT_TOKEN_ENDPOINT", "\"\"")
        }
        create("benchmark") {
            initWith(getByName("standalone"))
            matchingFallbacks += listOf("standalone", "release")
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

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.instrumentation.transformClassesWith(
            MusicKitSongIdVisitorFactory::class.java,
            InstrumentationScope.ALL,
        ) {}
        variant.instrumentation.setAsmFramesComputationMode(
            FramesComputationMode.COMPUTE_FRAMES_FOR_INSTRUMENTED_METHODS,
        )
    }
}

val verifyStandaloneCredential = tasks.register("verifyStandaloneCredential") {
    doLast {
        check(bundledDeveloperToken.isNotBlank()) {
            "Set AM_DEVELOPER_TOKEN_FILE to a signed developer token before building a standalone APK."
        }
        val parts = bundledDeveloperToken.split('.')
        check(parts.size == 3) { "Standalone developer token is not a JWT." }
        val header = String(Base64.getUrlDecoder().decode(parts[0]), Charsets.UTF_8)
        val payload = String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)
        check(header.contains("ES256")) { "Standalone developer token must use ES256." }
        val expiry = Regex("\"exp\"\\s*:\\s*(\\d+)").find(payload)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        check(expiry > Instant.now().epochSecond + 86_400L) {
            "The standalone credential must remain valid for at least one day. Export a fresh token."
        }
        logger.lifecycle("Standalone developer credential expires ${Instant.ofEpochSecond(expiry)}")
    }
}
tasks.matching { it.name == "preStandaloneBuild" || it.name == "preBenchmarkBuild" }
    .configureEach { dependsOn(verifyStandaloneCredential) }

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
    implementation("androidx.tv:tv-material:1.1.0")
    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.0")
    implementation("androidx.media3:media3-ui:1.11.0")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
