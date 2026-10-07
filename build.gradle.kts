plugins {
    kotlin("jvm") version libs.versions.kotlin.get()
    alias(libs.plugins.shadow)
    alias(libs.plugins.run.paper)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Compile against paper-api (a superset of the Bukkit/Spigot API that's easy to resolve).
    // DISCIPLINE: only Bukkit/Spigot-API methods may be used — no Paper-only calls — so the jar
    // runs on plain Spigot without NoSuchMethodError. See docs/DESIGN.md §0.
    compileOnly(libs.paper.api)
    // stdlib provided at runtime via plugin.yml `libraries:`, NOT shaded.
    compileOnly(libs.kotlin.stdlib)
    // Gson provided by the server at runtime; compile-only, not shaded.
    compileOnly(libs.gson)

    // Shaded (see relocate below) so text works on Spigot too.
    implementation(libs.adventure.platform.bukkit)
    implementation(libs.adventure.minimessage)

    testImplementation(libs.paper.api)
    testImplementation(libs.gson)
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(25)
}

tasks {
    build {
        dependsOn(shadowJar)
    }

    jar {
        archiveClassifier.set("thin")
    }

    shadowJar {
        archiveClassifier.set("")
        // Relocate Adventure so it never clashes with Paper's native copy or another plugin's shade.
        relocate("net.kyori", "com.tinyyana.awesomeArmorStandEditor.libs.kyori")
    }

    runServer {
        minecraftVersion(libs.versions.minecraft.get())
        jvmArgs("-Xms2G", "-Xmx2G")
    }

    test {
        useJUnitPlatform()
        // Golden fixtures: `GOLDEN_UPDATE=1 ./gradlew test` or `./gradlew test -Dgolden.update=true`
        // rewrites the expected files instead of comparing (see ContractFixturesTest).
        val goldenUpdate = System.getProperty("golden.update") == "true" || System.getenv("GOLDEN_UPDATE") == "1"
        systemProperty("golden.update", goldenUpdate.toString())
        inputs.property("goldenUpdate", goldenUpdate)
        if (goldenUpdate) outputs.upToDateWhen { false }
        inputs.dir("schema")
    }

    processResources {
        // The scene schema's source of truth is schema/scene.v3.schema.json (repo root, shared with
        // AASE Studio); the jar carries a copy for the validator.
        from(rootProject.file("schema")) { include("scene.v3.schema.json") }
        val props = mapOf("version" to version.toString(), "description" to project.description.toString())
        // Without this the task is UP-TO-DATE across a version bump and ships a stale plugin.yml,
        // so the jar filename and the version the server logs disagree.
        inputs.properties(props)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}
