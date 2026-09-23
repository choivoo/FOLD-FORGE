package com.foldforge.studio.core.apk

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import com.foldforge.studio.core.export.AndroidProjectExporter
import com.foldforge.studio.core.storage.ProjectFileSystem
import com.foldforge.studio.core.storage.SafePaths
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class ApkBuildOptions(
    val appName: String,
    val packageName: String,
    val versionName: String = "1.0.0",
    val versionCode: Int = 1,
)

data class ApkSigningKey(val name: String, val privateKey: PrivateKey, val certificates: List<X509Certificate>)

data class ApkBuildResult(
    val apk: File,
    val sizeBytes: Long,
    val sha256: String,
    val packageName: String,
    val label: String,
    val versionName: String,
    val assetFiles: Int,
    val signatureSchemes: List<String>,
    val log: List<String>,
)

class ApkBuildException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * On-device APK builder for web projects. Starts from a precompiled FOLD FORGE Player template APK
 * (a minimal, dependency-free WebView app), then:
 *  1. copies template entries (dropping old signatures and template web assets),
 *  2. rewrites the binary manifest: package, app label, versionName/versionCode,
 *  3. adds the project's web files under assets/www/,
 *  4. keeps resources.arsc STORED and 4-byte aligned,
 *  5. signs with APK Signature Scheme v1+v2+v3 (apksig) and verifies the result.
 * No Gradle or Android SDK is needed on the device.
 */
class ApkBuilder(private val templateApk: File) {

    fun build(webRoot: File, output: File, options: ApkBuildOptions, key: ApkSigningKey, log: (String) -> Unit = {}): ApkBuildResult {
        val messages = ArrayList<String>()
        fun step(msg: String) { messages += msg; log(msg) }
        AndroidProjectExporter.validatePackageName(options.packageName)?.let { throw ApkBuildException(it) }
        if (options.appName.isBlank()) throw ApkBuildException("App name must not be empty")
        if (!templateApk.isFile) throw ApkBuildException("Player template APK missing")
        if (!File(webRoot, "index.html").isFile) throw ApkBuildException("Web project has no index.html at its root")

        output.parentFile?.mkdirs()
        val unsigned = File(output.parentFile, output.name + ".unsigned")
        var assetCount = 0
        step("Template: ${templateApk.name} (${templateApk.length() / 1024} KB)")
        ZipFile(templateApk).use { zip ->
            val manifestEntry = zip.getEntry("AndroidManifest.xml") ?: throw ApkBuildException("Template has no AndroidManifest.xml")
            val editor = AxmlEditor(zip.getInputStream(manifestEntry).use { it.readBytes() })
            val ok = editor.setString("manifest", "package", options.packageName) &&
                editor.setString("application", "label", options.appName) &&
                editor.setString("manifest", "versionName", options.versionName) &&
                editor.setInt("manifest", "versionCode", options.versionCode)
            if (!ok) throw ApkBuildException("Template manifest is missing expected attributes")
            val manifestBytes = editor.toByteArray()
            step("Manifest patched: package=${options.packageName}, label=\"${options.appName}\", version=${options.versionName} (${options.versionCode})")

            unsigned.outputStream().buffered().use { raw ->
                ZipOutputStream(raw).use { zos ->
                    fun writeEntry(name: String, bytes: ByteArray, stored: Boolean) {
                        val e = ZipEntry(name)
                        e.time = FIXED_TIME
                        if (stored) {
                            val crc = CRC32().apply { update(bytes) }
                            e.method = ZipEntry.STORED
                            e.size = bytes.size.toLong()
                            e.compressedSize = bytes.size.toLong()
                            e.crc = crc.value
                            // Alignment of STORED entries is applied by apksig when signing.
                        } else {
                            e.method = ZipEntry.DEFLATED
                        }
                        zos.putNextEntry(e)
                        zos.write(bytes)
                        zos.closeEntry()
                    }
                    writeEntry("AndroidManifest.xml", manifestBytes, stored = false)
                    for (entry in zip.entries()) {
                        val name = entry.name
                        if (entry.isDirectory || name == "AndroidManifest.xml" || name.startsWith("assets/www/")) continue
                        if (name.startsWith("META-INF/") && (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".EC") || name.endsWith(".DSA") || name == "META-INF/MANIFEST.MF")) continue
                        val bytes = zip.getInputStream(entry).use { it.readBytes() }
                        writeEntry(name, bytes, stored = entry.method == ZipEntry.STORED || name == "resources.arsc")
                    }
                    webRoot.walkTopDown()
                        .onEnter { it == webRoot || !excluded(it.name) }
                        .filter { it.isFile && !excluded(it.name) }
                        .forEach { f ->
                            val rel = SafePaths.relativize(webRoot, f)
                            writeEntry("assets/www/$rel", f.readBytes(), stored = false)
                            assetCount++
                        }
                }
            }
        }
        step("Packaged $assetCount web asset file(s)")

        try {
            val signer = ApkSigner.SignerConfig.Builder(key.name, key.privateKey, key.certificates).build()
            ApkSigner.Builder(listOf(signer))
                .setInputApk(unsigned)
                .setOutputApk(output)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .build()
                .sign()
        } catch (e: Exception) {
            output.delete()
            throw ApkBuildException("Signing failed: ${e.message}", e)
        } finally {
            unsigned.delete()
        }
        step("Signed (v1 + v2 + v3) with key '${key.name}'")

        val verification = verify(output)
        if (!verification.first) {
            output.delete()
            throw ApkBuildException("Signature verification failed: ${verification.second.joinToString()}")
        }
        step("Verified signature: ${verification.second.joinToString()}")
        val info = ApkInspector.inspect(output)
        if (info.packageName != options.packageName) throw ApkBuildException("Output package mismatch (${info.packageName})")
        val sha = sha256(output)
        step("Output: ${output.name} ${output.length() / 1024} KB sha256=$sha")
        return ApkBuildResult(output, output.length(), sha, info.packageName, info.label ?: options.appName, info.versionName ?: "", assetCount, verification.second, messages)
    }

    private fun excluded(name: String) = name == ProjectFileSystem.META_DIR || name == ".git" || name == ".env" ||
        name.startsWith(".env.") || name.endsWith(".keystore") || name.endsWith(".jks") || name == "local.properties" || name == "node_modules"

    companion object {
        private const val FIXED_TIME = 1_704_067_200_000L // 2024-01-01, reproducible builds

        fun verify(apk: File): Pair<Boolean, List<String>> {
            val r = ApkVerifier.Builder(apk).build().verify()
            val schemes = buildList {
                if (r.isVerifiedUsingV1Scheme) add("v1")
                if (r.isVerifiedUsingV2Scheme) add("v2")
                if (r.isVerifiedUsingV3Scheme) add("v3")
            }
            return if (r.isVerified) true to schemes else false to r.errors.map { it.toString() }
        }

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

data class ApkInfo(val packageName: String, val label: String?, val versionName: String?, val versionCode: Int?, val entries: Int, val hasWebEntry: Boolean)

/** Reads package metadata from an APK's binary manifest (used to validate build outputs). */
object ApkInspector {
    fun inspect(apk: File): ApkInfo = ZipFile(apk).use { zip ->
        val manifest = zip.getEntry("AndroidManifest.xml") ?: throw ApkBuildException("Not an APK: AndroidManifest.xml missing")
        val editor = AxmlEditor(zip.getInputStream(manifest).use { it.readBytes() })
        val pkg = editor.attribute("manifest", "package")?.stringValue ?: throw ApkBuildException("Manifest has no package")
        val label = editor.attribute("application", "label")?.let { if (it.type == AxmlEditor.TYPE_STRING) it.stringValue else "@0x" + Integer.toHexString(it.data) }
        val vName = editor.attribute("manifest", "versionName")?.stringValue
        val vCode = editor.attribute("manifest", "versionCode")?.data
        ApkInfo(pkg, label, vName, vCode, zip.size(), zip.getEntry("assets/www/index.html") != null)
    }
}
