plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
    gradlePluginPortal()
}

dependencies {
    // puts the loom plugin on the main build script classpath, exactly like the upstream project
    implementation("net.fabricmc:fabric-loom:1.19.1")
}
