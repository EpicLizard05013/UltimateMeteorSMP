import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("java")
    id("com.gradleup.shadow") version "8.3.5"
}

group = "com.meteorsmp"
version = "1.0.0"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
    maven("https://oss.sonatype.org/content/repositories/snapshots/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") {
        exclude(group = "org.bukkit", module = "bukkit")
    }
    compileOnly("com.github.SkriptLang:Skript:2.16.2")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")
    implementation("com.zaxxer:HikariCP:6.2.1")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("all")
    relocate("com.zaxxer.hikari", "com.meteorsmp.libs.hikari")
    relocate("org.sqlite", "com.meteorsmp.libs.sqlite")
    minimize {
        exclude(dependency("org.xerial:sqlite-jdbc:.*"))
    }
}

tasks.build {
    dependsOn("shadowJar")
}

tasks.named<ProcessResources>("processResources") {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}