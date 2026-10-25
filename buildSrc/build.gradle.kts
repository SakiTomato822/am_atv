plugins {
    `java-library`
}

repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation(gradleApi())
    implementation("com.android.tools.build:gradle-api:8.13.2")
    implementation("org.ow2.asm:asm:9.8")
    implementation("org.ow2.asm:asm-tree:9.8")
    testImplementation("junit:junit:4.13.2")
    // A real Android JsonReader, rather than the throwing android.jar test stub.
    testImplementation("org.robolectric:android-all:9-robolectric-4913185-2")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.test {
    val sdkAar = layout.projectDirectory.file("../app/libs/mediaplayback-release-1.1.1.aar")
    inputs.files(sdkAar)
    systemProperty("amatv.musicKitSdkAar", sdkAar.asFile.absolutePath)
}
