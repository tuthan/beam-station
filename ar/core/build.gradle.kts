plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
}

dependencyLocking {
    lockAllConfigurations()
    // STRICT: a configuration resolved without lock state fails instead of resolving unlocked.
    lockMode.set(LockMode.STRICT)
}

// :core is plain Kotlin on the JVM, tested there. It must not see Android APIs or depend on another project.
val coreSources = fileTree("src") { include("**/*.kt") }
val forbiddenImport = Regex("""^\s*import\s+(android|androidx|com\.android)\.""")

val verifyCoreBoundary = tasks.register("verifyCoreBoundary") {
    group = "verification"
    description = "Fails if :core imports Android APIs."
    inputs.files(coreSources)
    doLast {
        val violations = coreSources.files.flatMap { file ->
            file.readLines().withIndex()
                .filter { forbiddenImport.containsMatchIn(it.value) }
                .map { "${file.relativeTo(projectDir)}:${it.index + 1}: ${it.value.trim()}" }
        }
        if (violations.isNotEmpty()) {
            throw GradleException("Android imports in :core:\n" + violations.joinToString("\n"))
        }
    }
}

tasks.named("check") {
    dependsOn(verifyCoreBoundary)
}
