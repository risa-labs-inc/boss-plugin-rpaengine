import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    id("org.jetbrains.compose") version "1.10.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0"
}

group = "ai.rever.boss.plugin.dynamic"
version = "1.0.5"

// Local BossConsole path for development builds
val bossConsolePath = "${System.getProperty("user.home")}/Development/BossConsole"
val useLocalBuild = file(bossConsolePath).exists() &&
    file("$bossConsolePath/plugins/plugin-api/build/libs/plugin-api-desktop-1.0.9.jar").exists()

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
    if (useLocalBuild) {
        flatDir {
            dirs(
                "$bossConsolePath/plugins/plugin-api/build/libs",
                "$bossConsolePath/plugins/plugin-api-browser/build/libs",
                "$bossConsolePath/plugins/plugin-ui-core/build/libs",
                "$bossConsolePath/plugins/plugin-scrollbar/build/libs",
                "$bossConsolePath/plugins/plugin-bookmark-types/build/libs"
            )
        }
    }
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    // Plugin API - use local build if available, otherwise Maven Central
    if (useLocalBuild) {
        implementation(files("$bossConsolePath/plugins/plugin-api/build/libs/plugin-api-desktop-1.0.9.jar"))
        implementation(files("$bossConsolePath/plugins/plugin-api-browser/build/libs/plugin-api-browser-desktop-1.0.4.jar"))
        implementation(files("$bossConsolePath/plugins/plugin-ui-core/build/libs/plugin-ui-core-desktop-1.0.4.jar"))
        implementation(files("$bossConsolePath/plugins/plugin-scrollbar/build/libs/plugin-scrollbar-desktop-1.0.4.jar"))
        implementation(files("$bossConsolePath/plugins/plugin-bookmark-types/build/libs/plugin-bookmark-types-desktop-1.0.4.jar"))
    } else {
        implementation("com.risaboss:plugin-api-desktop:1.0.10")
        implementation("com.risaboss:plugin-api-browser-desktop:1.0.4")
        implementation("com.risaboss:plugin-ui-core-desktop:1.0.4")
        implementation("com.risaboss:plugin-scrollbar-desktop:1.0.4")
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
