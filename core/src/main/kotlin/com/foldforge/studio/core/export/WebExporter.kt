package com.foldforge.studio.core.export

import com.foldforge.studio.core.storage.ProjectFileSystem
import com.foldforge.studio.core.storage.SafePaths
import com.foldforge.studio.core.storage.ZipTools
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CiWorkflow {
    /** GitHub Actions workflow that tests, lints and builds a debug APK, then uploads it. */
    fun android(appName: String): String = """
        name: Android CI

        on:
          push:
          pull_request:
          workflow_dispatch:

        jobs:
          build:
            runs-on: ubuntu-latest
            steps:
              - uses: actions/checkout@v4
              - uses: actions/setup-java@v4
                with:
                  distribution: temurin
                  java-version: '17'
              - uses: gradle/actions/setup-gradle@v4
              - name: Make gradlew executable
                run: chmod +x ./gradlew
              - name: Unit tests
                run: ./gradlew test
              - name: Lint
                run: ./gradlew lint
              - name: Build debug APK
                run: ./gradlew assembleDebug
              - name: Upload APK
                uses: actions/upload-artifact@v4
                with:
                  name: ${appName.replace(Regex("[^A-Za-z0-9._-]"), "-")}-debug-apk
                  path: app/build/outputs/apk/debug/*.apk
                  if-no-files-found: error
    """.trimIndent() + "\n"
}

object WebExporter {
    private fun excluded(rel: String): Boolean {
        val first = rel.substringBefore('/')
        val name = rel.substringAfterLast('/')
        return first == ProjectFileSystem.META_DIR || first == ".git" || first == "node_modules" ||
            name == ".env" || name.startsWith(".env.") || name.endsWith(".keystore") || name.endsWith(".jks") ||
            name == "local.properties"
    }

    /** Copies deployable web files (no metadata, VCS data or secrets) to [target]. */
    fun exportWebBuild(projectRoot: File, target: File): Int {
        target.mkdirs()
        var count = 0
        projectRoot.walkTopDown()
            .onEnter { it == projectRoot || !excluded(SafePaths.relativize(projectRoot, it)) }
            .filter { it.isFile }
            .forEach { f ->
                val rel = SafePaths.relativize(projectRoot, f)
                if (excluded(rel)) return@forEach
                val dest = SafePaths.resolve(target, rel)
                dest.parentFile?.mkdirs()
                f.copyTo(dest, overwrite = true)
                count++
            }
        File(target, "HOW_TO_RUN.md").writeText(
            """
            # How to run

            This is a static web build exported by FOLD FORGE.

            - Serve this folder with any static web server, e.g. `npx serve .` or `python3 -m http.server 8080`
            - Then open http://localhost:8080 in a browser.
            - ES modules and import maps require http(s) — opening index.html via file:// may not work.
            """.trimIndent() + "\n",
        )
        return count
    }

    /** Zips the project. [includeGit] keeps the `.git` folder (Export "Git Repository"). */
    fun exportZip(projectRoot: File, out: OutputStream, includeGit: Boolean = false, includeMeta: Boolean = true): Int =
        ZipTools.zipDirectory(projectRoot, out) { rel ->
            val first = rel.substringBefore('/')
            when {
                first == ".git" -> !includeGit
                rel.startsWith("${ProjectFileSystem.META_DIR}/snapshots") -> true
                rel.startsWith("${ProjectFileSystem.META_DIR}/journal") -> true
                first == ProjectFileSystem.META_DIR -> !includeMeta
                else -> false
            }
        }

    fun zipFileName(projectName: String, now: Date = Date()): String {
        val base = projectName.replace(Regex("[^A-Za-z0-9]+"), "")
            .ifEmpty { "Project" }
        return "${base}_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(now)}.zip"
    }
}
