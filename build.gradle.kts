import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("net.fabricmc.fabric-loom")
    kotlin("jvm") version "2.4.20"
}

val minecraftVersion = property("minecraft_version") as String
val loaderVersion = property("loader_version") as String
val fabricKotlinVersion = property("fabric_kotlin_version") as String
val fabricVersion = property("fabric_version") as String
val modVersion = property("mod_version") as String
val mavenGroup = property("maven_group") as String
val modName = property("mod_name") as String

version = modVersion
group = mavenGroup

base { archivesName.set(modName) }

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")

    // upstream also uses plain implementation for these
    implementation("net.fabricmc:fabric-loader:$loaderVersion")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricVersion")
    implementation("net.fabricmc:fabric-language-kotlin:$fabricKotlinVersion")

    // unremapped (named) build of the installed NoammAddons jar.
    // Refresh after updating NoammAddons: NoammAddons/build/tmp/intermediateJars/NoammAddons-*-dev.jar
    compileOnly(files("libs/NoammAddons-dev.jar"))
}

// jar is labelled with the Minecraft version it was built for: NoammWaterAddon-<mod>-<mc>.jar
tasks.named<Jar>("jar") {
    archiveVersion.set("$modVersion-$minecraftVersion")
}

tasks.withType<JavaCompile>().configureEach { options.release.set(25) }
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_25) }
}

tasks.named<ProcessResources>("processResources") {
    inputs.property("version", project.version)
    filesMatching("fabric.mod.json") {
        expand("version" to project.version, "minecraft_version" to minecraftVersion)
    }
}
