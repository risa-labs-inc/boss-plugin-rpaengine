import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    id("org.jetbrains.compose") version "1.10.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0"
}

group = "ai.rever.boss.plugin.dynamic"
version = "1.0.5"

// Auto-detect CI environment
val useLocalDependencies = System.getenv("CI") != "true"
val bossPluginApiPath = "../boss-plugin-api"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    if (useLocalDependencies) {
        // Local development: use boss-plugin-api JAR from sibling repo
        compileOnly(files("$bossPluginApiPath/build/libs/boss-plugin-api-1.0.18.jar"))
    } else {
        // CI: use downloaded JAR
        compileOnly(files("build/downloaded-deps/boss-plugin-api.jar"))
    }

    // Compose dependencies
    implementation(compose.desktop.currentOs)
    implementation(compose.runtime)
    implementation(compose.ui)
    implementation(compose.foundation)
    implementation(compose.material)
    implementation(compose.materialIconsExtended)

    // Compose Icons (FeatherIcons)
    implementation("br.com.devsrsouza.compose.icons:feather:1.1.1")

    // Decompose for ComponentContext
    implementation("com.arkivanov.decompose:decompose:3.3.0")
    implementation("com.arkivanov.essenty:lifecycle:2.5.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // Serialization for JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
}

// Task to build plugin JAR with compiled classes only
tasks.register<Jar>("buildPluginJar") {
    archiveFileName.set("boss-plugin-rpaengine-${version}.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    
    manifest {
        attributes(
            "Implementation-Title" to "BOSS RPA Engine Plugin",
            "Implementation-Version" to version,
            "Main-Class" to "ai.rever.boss.plugin.dynamic.rpaengine.RpaengineDynamicPlugin"
        )
    }
    
    // Include compiled classes
    from(sourceSets.main.get().output)
    
    // Include plugin manifest
    from("src/main/resources")
}

tasks.build {
    dependsOn("buildPluginJar")
}
