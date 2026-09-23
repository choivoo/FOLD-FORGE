package com.foldforge.studio.core.export

import com.foldforge.studio.core.apk.ApkBuildException
import com.foldforge.studio.core.apk.ApkBuildOptions
import com.foldforge.studio.core.apk.ApkBuilder
import com.foldforge.studio.core.apk.ApkInspector
import com.foldforge.studio.core.apk.ApkSigningKey
import com.foldforge.studio.core.apk.AxmlEditor
import com.foldforge.studio.core.assets.AssetScanner
import com.foldforge.studio.core.assets.ImageInspector
import com.foldforge.studio.core.assets.ModelInspector
import com.foldforge.studio.core.assets.ProceduralAssets
import com.foldforge.studio.core.storage.ProjectFileSystem
import com.foldforge.studio.core.storage.ProjectStore
import com.foldforge.studio.core.tempDir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.ZipFile

class ExportApkAssetsTest {

    @Test fun `android wrapper export has a complete buildable structure`() {
        val store = ProjectStore(tempDir())
        val p = store.createFromTemplate("Forge Runner", "forge-runner")
        p.fs.writeText(".env", "SECRET=1")
        val out = File(tempDir(), "export")
        val opts = AndroidExportOptions("Forge Runner", AndroidProjectExporter.packageNameFor("Forge Runner"))
        val written = AndroidProjectExporter().exportFromWeb(p.root, out, opts)
        assertTrue(written.isNotEmpty())
        assertEquals(emptyList<String>(), AndroidProjectExporter().validate(out))
        assertTrue(File(out, "app/src/main/assets/www/runner.js").isFile)
        assertFalse("secrets must not be exported", File(out, "app/src/main/assets/www/.env").exists())
        assertFalse(File(out, "app/src/main/assets/www/.foldforge").exists())
        assertTrue(File(out, ".github/workflows/android.yml").readText().contains("assembleDebug"))
        assertTrue(File(out, "app/src/main/java/com/foldforge/generated/forgerunner/MainActivity.kt").readText().contains("allowFileAccess = false"))
        assertThrows(java.io.IOException::class.java) { AndroidProjectExporter().exportFromWeb(p.root, out, opts) } // non-empty target
        assertEquals("com.foldforge.generated.forgerunner", opts.packageName)
        assertEquals("com.foldforge.generated.app3d", AndroidProjectExporter.packageNameFor("3D!"))
        assertTrue(AndroidProjectExporter.validatePackageName("Com.Bad") != null)
        assertTrue(AndroidProjectExporter.validatePackageName("com.class.x") != null)
        assertEquals(null, AndroidProjectExporter.validatePackageName("com.example.game"))
        System.getProperty("foldforge.exportDir")?.let { dest -> out.copyRecursively(File(dest), overwrite = true) }
    }

    @Test fun `web build export and zip naming`() {
        val p = ProjectStore(tempDir()).createFromTemplate("Monster RPG", "rpg3d")
        val out = File(tempDir(), "web")
        val n = WebExporter.exportWebBuild(p.root, out)
        assertTrue(n >= 9)
        assertTrue(File(out, "HOW_TO_RUN.md").isFile)
        assertFalse(File(out, ".foldforge").exists())
        assertTrue(WebExporter.zipFileName("Monster RPG", java.util.Date(0)).startsWith("MonsterRPG_1970"))
    }

    private fun testKey(): ApkSigningKey {
        val dir = tempDir()
        val ks = File(dir, "test.p12")
        val keytool = File(System.getProperty("java.home"), "bin/keytool").absolutePath
        val proc = ProcessBuilder(
            keytool, "-genkeypair", "-alias", "ff", "-keyalg", "RSA", "-keysize", "2048", "-validity", "365",
            "-dname", "CN=FOLD FORGE Test", "-storetype", "PKCS12", "-keystore", ks.absolutePath,
            "-storepass", "changeit", "-keypass", "changeit",
        ).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText()
        assertEquals(output, 0, proc.waitFor())
        val store = KeyStore.getInstance("PKCS12").apply { ks.inputStream().use { load(it, "changeit".toCharArray()) } }
        return ApkSigningKey("ff", store.getKey("ff", "changeit".toCharArray()) as PrivateKey, listOf(store.getCertificate("ff") as X509Certificate))
    }

    private fun templateApk(): File {
        val f = File(System.getProperty("foldforge.playerTemplateApk") ?: "")
        assumeTrue("player template APK not built", f.isFile)
        return f
    }

    @Test fun `binary manifest editing round trips`() {
        val bytes = ZipFile(templateApk()).use { z -> z.getInputStream(z.getEntry("AndroidManifest.xml")).readBytes() }
        val editor = AxmlEditor(bytes)
        assertEquals("com.foldforge.player.template", editor.attribute("manifest", "package")?.stringValue)
        assertTrue(editor.setString("manifest", "package", "com.example.mygame"))
        assertTrue(editor.setString("application", "label", "몬스터 RPG"))
        assertTrue(editor.setInt("manifest", "versionCode", 42))
        val reparsed = AxmlEditor(editor.toByteArray())
        assertEquals("com.example.mygame", reparsed.attribute("manifest", "package")?.stringValue)
        assertEquals("몬스터 RPG", reparsed.attribute("application", "label")?.stringValue)
        assertEquals(42, reparsed.attribute("manifest", "versionCode")?.data)
        assertEquals("com.foldforge.player.PlayerActivity", reparsed.attribute("activity", "name")?.stringValue)
    }

    @Test fun `on-device apk builder produces a signed verified apk`() {
        val template = templateApk()
        val p = ProjectStore(tempDir()).createFromTemplate("Forge Runner", "forge-runner")
        val web = File(tempDir(), "web")
        WebExporter.exportWebBuild(p.root, web)
        val out = File(tempDir(), "FoldForge-ForgeRunner-1.0.0.apk")
        val logs = mutableListOf<String>()
        val result = ApkBuilder(template).build(web, out, ApkBuildOptions("Forge Runner", "com.foldforge.generated.forgerunner", "1.0.0", 3), testKey()) { logs += it }
        assertTrue(out.isFile && out.length() > 1000)
        assertEquals(64, result.sha256.length)
        assertTrue(result.signatureSchemes.containsAll(listOf("v2", "v3")))
        val info = ApkInspector.inspect(out)
        assertEquals("com.foldforge.generated.forgerunner", info.packageName)
        assertEquals("Forge Runner", info.label)
        assertEquals("1.0.0", info.versionName)
        assertEquals(3, info.versionCode)
        assertTrue(info.hasWebEntry)
        ZipFile(out).use { z ->
            assertNotNull(z.getEntry("assets/www/runner.js"))
            assertNotNull(z.getEntry("classes.dex"))
            assertTrue(z.getEntry("assets/www/HOW_TO_RUN.md") != null)
        }
        assertTrue(logs.any { it.startsWith("Verified") })
        // resources.arsc must be stored + 4-byte aligned (required for targetSdk >= 30)
        val zipalign = File(System.getProperty("foldforge.buildTools") ?: "").listFiles()?.sortedDescending()?.map { File(it, "zipalign") }?.firstOrNull { it.canExecute() }
        if (zipalign != null) {
            val proc = ProcessBuilder(zipalign.absolutePath, "-c", "-p", "4", out.absolutePath).redirectErrorStream(true).start()
            val text = proc.inputStream.bufferedReader().readText()
            assertEquals("zipalign -c failed: $text", 0, proc.waitFor())
        }
        System.getProperty("foldforge.apkOut")?.let { out.copyTo(File(it), overwrite = true) }
        assertThrows(ApkBuildException::class.java) {
            ApkBuilder(template).build(web, File(tempDir(), "x.apk"), ApkBuildOptions("X", "Bad.Package"), testKey())
        }
    }

    @Test fun `glb inspector validates container and counts geometry`() {
        val json = """{"asset":{"version":"2.0","generator":"test"},"meshes":[{"name":"Cube","primitives":[{"attributes":{"POSITION":0},"indices":1}]}],"accessors":[{"count":24},{"count":36}],"materials":[{"name":"Metal"}],"animations":[{"name":"Spin"}]}"""
        var jsonBytes = json.toByteArray()
        while (jsonBytes.size % 4 != 0) jsonBytes += ' '.code.toByte()
        val total = 12 + 8 + jsonBytes.size
        val glb = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x46546C67).putInt(2).putInt(total).putInt(jsonBytes.size).putInt(0x4E4F534A).put(jsonBytes).array()
        val info = ModelInspector.inspectGlb(glb)
        assertTrue(info.error, info.valid)
        assertEquals(24L, info.vertices)
        assertEquals(12L, info.triangles)
        assertEquals(listOf("Metal"), info.materials)
        assertEquals(listOf("Spin"), info.animations)
        assertFalse(ModelInspector.inspectGlb(glb.copyOf(glb.size - 4)).valid)
        assertFalse(ModelInspector.inspectGlb("not a glb file at all".toByteArray()).valid)
    }

    @Test fun `image headers and asset dependency scan`() {
        val png = ByteBuffer.allocate(24).put(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0, 0, 0, 13, 'I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte()))
            .putInt(640).putInt(480).array()
        assertEquals(640, ImageInspector.inspect(png)!!.width)
        val fs = ProjectFileSystem(tempDir())
        fs.writeText("index.html", "<img src=\"assets/hero.png\">")
        fs.writeBytes("assets/hero.png", png)
        fs.writeBytes("assets/unused.png", png)
        val entries = AssetScanner.scan(fs)
        assertEquals(listOf("assets/unused.png"), AssetScanner.unused(entries).map { it.path })
        assertEquals(480, entries.first { it.path == "assets/hero.png" }.image?.height)
        assertTrue(fs.exists("assets/unused.png")) // never deleted automatically
    }

    @Test fun `procedural asset module generation`() {
        val src = ProceduralAssets.module(ProceduralAssets.KINDS)
        ProceduralAssets.KINDS.forEach { assertTrue(it, src.contains("userData.kind = '$it'") || src.contains("kind = '$it'")) }
        assertTrue(src.startsWith("// Procedural"))
        assertThrows(IllegalArgumentException::class.java) { ProceduralAssets.module(listOf("dragon")) }
        val bos = ByteArrayOutputStream(); bos.write(src.toByteArray()); assertTrue(bos.size() > 1000)
    }
}
