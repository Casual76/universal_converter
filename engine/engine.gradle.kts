// SPDX-License-Identifier: GPL-2.0-only
//
// La costruzione del motore di conversione: fa parte del motore, non dell'app.
//
// Convert to it! e' due programmi nella stessa APK (vedi README.md): l'app Kotlin (GPL-3.0, in app/) e
// il motore di conversione (GPL-2.0, in convert/ e in questa cartella). Questi task sono "gli script
// che controllano la compilazione" del motore, che la GPL-2.0 chiede di distribuire con i suoi
// sorgenti: per questo stanno qui, accanto al ponte e sotto la sua licenza, e app/build.gradle.kts li
// applica soltanto. All'app tornano tre valori, in `extra`: la versione del motore, l'URL dei
// pacchetti scaricabili e la cartella degli asset da mettere nell'APK.

import java.security.MessageDigest

/* -------------------------------------------------------------------------- */
/* Conversion engine build                                                     */
/* -------------------------------------------------------------------------- */

val engineDir = rootProject.layout.projectDirectory.dir("convert")
val engineDistDir = engineDir.dir("dist-android")
val engineGeneratedDir = layout.buildDirectory.dir("generated/engine")
val engineAssetsDir = engineGeneratedDir.map { it.dir("assets") }
val enginePackDir = engineGeneratedDir.map { it.dir("pack") }
/** Our own additions to the engine, kept outside the pristine submodule. */
val bridgeDir = rootProject.layout.projectDirectory.dir("engine/bridge")
/** Committed so ordinary builds never have to boot every WASM engine. */
val formatCacheFile = rootProject.layout.projectDirectory.file("engine/cache.json")

val isWindows = System.getProperty("os.name").lowercase().contains("windows")
fun cmd(name: String) = if (isWindows) "$name.cmd" else name

/** Everything at or above this size is downloaded on demand instead of shipped. */
val bundleThreshold = (project.findProperty("engine.bundleThresholdBytes") as String?)?.toLong() ?: 3_145_728L

/** Short commit of the engine submodule, used to version the downloadable pack. */
val engineVersion: String by lazy {
    runCatching {
        providers.exec {
            commandLine("git", "-C", engineDir.asFile.absolutePath, "rev-parse", "--short=10", "HEAD")
        }.standardOutput.asText.get().trim()
    }.getOrDefault("unknown")
}

val engineAssetBaseUrl: String = (project.findProperty("engine.assetBaseUrl") as String?)
    ?: "https://github.com/Casual76/universal_converter/releases/download"

tasks.register<Exec>("engineNpmInstall") {
    description = "Installs the conversion engine's npm dependencies."
    workingDir = engineDir.asFile
    commandLine(cmd("npm"), "install", "--no-audit", "--no-fund")
    inputs.file(engineDir.file("package.json"))
    inputs.file(engineDir.file("package-lock.json"))
    outputs.dir(engineDir.dir("node_modules")).withPropertyName("nodeModules")
}

/**
 * Copies our headless entry point into the engine checkout.
 *
 * These files are additions, never edits: the submodule stays byte for byte
 * identical to upstream, so pulling new handlers is a plain `git pull` with no
 * conflicts to resolve.
 */
val bridgeFiles = mapOf(
    "engine.ts" to "src/android/engine.ts",
    "build-cache.mjs" to "src/android/build-cache.mjs",
    "smoke-test.mjs" to "src/android/smoke-test.mjs",
    "engine.html" to "engine.html",
    "vite.android.config.js" to "vite.android.config.js"
)

tasks.register("engineSyncBridge") {
    description = "Copies the headless bridge into the engine checkout."
    inputs.files(bridgeFiles.keys.map { bridgeDir.file(it) }).withPropertyName("bridge")
    // Listing the individual destinations keeps Gradle from treating the whole
    // engine checkout as this task's output.
    outputs.files(bridgeFiles.values.map { engineDir.file(it) })
    doLast {
        bridgeFiles.forEach { (source, destination) ->
            val target = engineDir.file(destination).asFile
            target.parentFile.mkdirs()
            bridgeDir.file(source).asFile.copyTo(target, overwrite = true)
        }
    }
}

tasks.register<Exec>("engineBuild") {
    description = "Builds the headless conversion engine bundle."
    dependsOn("engineNpmInstall", "engineSyncBridge")
    workingDir = engineDir.asFile
    commandLine(cmd("npx"), "vite", "build", "--config", "vite.android.config.js")
    inputs.dir(engineDir.dir("src")).withPropertyName("engineSource")
    inputs.dir(bridgeDir).withPropertyName("bridgeSource")
    inputs.file(engineDir.file("vite.config.js"))
    outputs.dir(engineDistDir).withPropertyName("engineDist")
}

tasks.register<Exec>("engineFormatCache") {
    description = "Regenerates the precomputed format cache (slow: boots every handler)."
    dependsOn("engineBuild")
    workingDir = engineDir.asFile
    commandLine("node", "src/android/build-cache.mjs", formatCacheFile.asFile.absolutePath)
    outputs.file(formatCacheFile)
    // Only run when explicitly asked or when the committed cache is missing.
    onlyIf { !formatCacheFile.asFile.exists() || project.hasProperty("refreshFormatCache") }
}

/**
 * Splits the engine build in two: light files travel inside the APK, heavy ones
 * become a downloadable pack. Four WASM blobs account for roughly 80% of the
 * engine's size while being needed by a minority of conversions, so shipping
 * them would triple the install for no benefit to most users.
 */
tasks.register("engineSplitAssets") {
    description = "Splits the engine build into bundled assets and an on-demand pack."
    dependsOn("engineBuild", "engineFormatCache")

    inputs.dir(engineDistDir).withPropertyName("engineDist")
    inputs.file(formatCacheFile).withPropertyName("formatCache")
    inputs.property("threshold", bundleThreshold)
    inputs.property("engineVersion", engineVersion)
    outputs.dir(engineAssetsDir).withPropertyName("bundledAssets")
    outputs.dir(enginePackDir).withPropertyName("onDemandPack")

    doLast {
        val dist = engineDistDir.asFile
        val bundled = engineAssetsDir.get().asFile.also { it.deleteRecursively(); it.mkdirs() }
        val pack = enginePackDir.get().asFile.also { it.deleteRecursively(); it.mkdirs() }
        val convertDir = File(bundled, "convert").also { it.mkdirs() }

        // The entry chunk must always be present: it is the engine itself.
        val entryScript = File(dist, "engine.html").readText()
            .let { Regex("""src="[^"]*/(assets/[^"]+)"""").find(it)?.groupValues?.get(1) }

        val remote = mutableListOf<Map<String, Any>>()
        var bundledBytes = 0L
        var remoteBytes = 0L

        dist.walkTopDown().filter { it.isFile }.forEach { file ->
            val relative = file.relativeTo(dist).invariantSeparatorsPath
            val onDemand = file.length() >= bundleThreshold && relative != entryScript
            if (onDemand) {
                val sha = MessageDigest.getInstance("SHA-256")
                    .digest(file.readBytes())
                    .joinToString("") { "%02x".format(it) }
                // Published names are content addressed. Vite rewrites the hash
                // inside a chunk's file name whenever the bundle is rebuilt, even
                // when the bytes are identical, which would silently invalidate an
                // already published pack.
                val extension = file.name.substringAfterLast('.', "")
                val stem = file.name.removeSuffix(".$extension").replace(Regex("-[A-Za-z0-9_-]{8}$"), "")
                val flat = "$stem-${sha.take(12)}" + if (extension.isEmpty()) "" else ".$extension"
                file.copyTo(File(pack, flat), overwrite = true)
                remote += mapOf(
                    "path" to relative,
                    "asset" to flat,
                    "size" to file.length(),
                    "sha256" to sha
                )
                remoteBytes += file.length()
            } else {
                file.copyTo(File(convertDir, relative).apply { parentFile.mkdirs() }, overwrite = true)
                bundledBytes += file.length()
            }
        }

        formatCacheFile.asFile.copyTo(File(convertDir, "cache.json"), overwrite = true)

        val sorted = remote.sortedBy { it["path"] as String }
        // Identifies the payload itself, so an unrelated rebuild does not force
        // a republish and a real change always gets its own release.
        val packVersion = MessageDigest.getInstance("SHA-256")
            .digest(sorted.joinToString("|") { "${it["path"]}:${it["sha256"]}" }.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(10)

        val manifest = buildString {
            append("""{"engineVersion":"""").append(engineVersion)
            append("""","packVersion":"""").append(packVersion).append("""","assets":[""")
            sorted.forEachIndexed { index, asset ->
                if (index > 0) append(",")
                append("""{"path":"${asset["path"]}","asset":"${asset["asset"]}",""")
                append(""""size":${asset["size"]},"sha256":"${asset["sha256"]}"}""")
            }
            append("]}")
        }
        File(bundled, "engine-assets.json").writeText(manifest)

        File(bundled, "engine-pack-version.txt").writeText(packVersion)

        logger.lifecycle(
            "Engine %s (pack %s): %.1f MB bundled, %.1f MB on demand across %d files."
                .format(engineVersion, packVersion, bundledBytes / 1048576.0, remoteBytes / 1048576.0, remote.size)
        )
    }
}

tasks.register<Zip>("engineAssetPack") {
    description = "Zips the on-demand engine pack for uploading to a release."
    dependsOn("engineSplitAssets")
    from(enginePackDir)
    archiveFileName.set("engine-pack.zip")
    destinationDirectory.set(layout.buildDirectory.dir("outputs/engine"))
}

// Quello che l'app legge: la versione (per BuildConfig e per le release del pacchetto), dove stanno i
// motori scaricabili, e la cartella che diventa assets/ nell'APK.
extra["engineVersion"] = engineVersion
extra["engineAssetBaseUrl"] = engineAssetBaseUrl
extra["engineAssetsDir"] = engineAssetsDir
