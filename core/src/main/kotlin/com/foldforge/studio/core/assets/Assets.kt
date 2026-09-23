package com.foldforge.studio.core.assets

import com.foldforge.studio.core.model.FileKind
import com.foldforge.studio.core.storage.ProjectFileSystem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ModelInfo(
    val format: String,
    val valid: Boolean,
    val error: String? = null,
    val meshes: Int = 0,
    val primitives: Int = 0,
    val vertices: Long = 0,
    val triangles: Long = 0,
    val materials: List<String> = emptyList(),
    val textures: Int = 0,
    val images: Int = 0,
    val animations: List<String> = emptyList(),
    val generator: String? = null,
)

data class ImageInfo(val format: String, val width: Int, val height: Int)

data class AssetEntry(
    val path: String,
    val kind: FileKind,
    val size: Long,
    val referenced: Boolean,
    val image: ImageInfo? = null,
    val model: ModelInfo? = null,
)

object ModelInspector {
    private val json = Json { ignoreUnknownKeys = true }

    fun inspect(file: File): ModelInfo = when (file.extension.lowercase()) {
        "glb" -> inspectGlb(file.readBytes())
        "gltf" -> inspectGltfJson(file.readText(), "glTF")
        "obj" -> inspectObj(file)
        else -> ModelInfo(file.extension.uppercase(), false, "Unsupported model format")
    }

    /** Validates the GLB container (header, chunk table) and reads the JSON chunk. */
    fun inspectGlb(bytes: ByteArray): ModelInfo {
        if (bytes.size < 20) return ModelInfo("GLB", false, "File too small")
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bb.int != 0x46546C67) return ModelInfo("GLB", false, "Bad magic (not a binary glTF file)")
        val version = bb.int
        val length = bb.int
        if (version != 2) return ModelInfo("GLB", false, "Unsupported glTF version $version")
        if (length != bytes.size) return ModelInfo("GLB", false, "Declared length $length ≠ actual ${bytes.size}")
        val chunkLen = bb.int
        val chunkType = bb.int
        if (chunkType != 0x4E4F534A) return ModelInfo("GLB", false, "First chunk is not JSON")
        if (chunkLen < 0 || 20 + chunkLen > bytes.size) return ModelInfo("GLB", false, "JSON chunk exceeds file")
        val jsonText = String(bytes, 20, chunkLen, Charsets.UTF_8)
        var offset = 20 + chunkLen
        var binLen = 0
        if (offset + 8 <= bytes.size) {
            bb.position(offset)
            binLen = bb.int
            val binType = bb.int
            if (binType != 0x004E4942) return ModelInfo("GLB", false, "Second chunk is not BIN")
            offset += 8 + binLen
            if (offset > bytes.size) return ModelInfo("GLB", false, "BIN chunk exceeds file")
        }
        return inspectGltfJson(jsonText, "GLB")
    }

    fun inspectGltfJson(text: String, format: String): ModelInfo {
        val root = try { json.parseToJsonElement(text) as JsonObject } catch (e: Exception) { return ModelInfo(format, false, "Invalid glTF JSON") }
        fun arr(k: String) = root[k] as? JsonArray ?: JsonArray(emptyList())
        val accessors = arr("accessors")
        var vertices = 0L
        var triangles = 0L
        var primitives = 0
        for (mesh in arr("meshes")) {
            val prims = (mesh as? JsonObject)?.get("primitives") as? JsonArray ?: continue
            for (p in prims) {
                primitives++
                val po = p as? JsonObject ?: continue
                val attrs = po["attributes"] as? JsonObject
                val posIdx = (attrs?.get("POSITION") as? JsonPrimitive)?.intOrNull
                val vCount = posIdx?.let { ((accessors.getOrNull(it) as? JsonObject)?.get("count") as? JsonPrimitive)?.intOrNull } ?: 0
                vertices += vCount
                val mode = (po["mode"] as? JsonPrimitive)?.intOrNull ?: 4
                val idx = (po["indices"] as? JsonPrimitive)?.intOrNull
                val iCount = idx?.let { ((accessors.getOrNull(it) as? JsonObject)?.get("count") as? JsonPrimitive)?.intOrNull } ?: vCount
                if (mode == 4) triangles += iCount / 3
                else if (mode == 5 || mode == 6) triangles += (iCount - 2).coerceAtLeast(0)
            }
        }
        val version = ((root["asset"] as? JsonObject)?.get("version") as? JsonPrimitive)?.contentOrNull
        if (version == null || !version.startsWith("2")) return ModelInfo(format, false, "Missing or unsupported asset.version")
        fun names(k: String, prefix: String) = arr(k).mapIndexed { i, e -> ((e as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull ?: "$prefix $i" }
        return ModelInfo(
            format = format, valid = true,
            meshes = arr("meshes").size, primitives = primitives, vertices = vertices, triangles = triangles,
            materials = names("materials", "Material"), textures = arr("textures").size, images = arr("images").size,
            animations = names("animations", "Animation"),
            generator = ((root["asset"] as? JsonObject)?.get("generator") as? JsonPrimitive)?.contentOrNull,
        )
    }

    private fun inspectObj(file: File): ModelInfo {
        var v = 0L
        var f = 0L
        val materials = LinkedHashSet<String>()
        var objects = 0
        file.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                when {
                    line.startsWith("v ") -> v++
                    line.startsWith("f ") -> f += (line.trim().split(Regex("\\s+")).size - 3).coerceAtLeast(1)
                    line.startsWith("usemtl ") -> materials += line.removePrefix("usemtl ").trim()
                    line.startsWith("o ") || line.startsWith("g ") -> objects++
                }
            }
        }
        return ModelInfo("OBJ", v > 0, if (v == 0L) "No vertices found" else null, meshes = maxOf(1, objects), primitives = maxOf(1, objects), vertices = v, triangles = f, materials = materials.toList())
    }
}

object ImageInspector {
    fun inspect(bytes: ByteArray): ImageInfo? {
        if (bytes.size >= 24 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()) {
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            return ImageInfo("PNG", bb.getInt(16), bb.getInt(20))
        }
        if (bytes.size > 4 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
            var i = 2
            while (i + 9 < bytes.size) {
                if (bytes[i] != 0xFF.toByte()) { i++; continue }
                val marker = bytes[i + 1].toInt() and 0xFF
                val len = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
                if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                    val h = ((bytes[i + 5].toInt() and 0xFF) shl 8) or (bytes[i + 6].toInt() and 0xFF)
                    val w = ((bytes[i + 7].toInt() and 0xFF) shl 8) or (bytes[i + 8].toInt() and 0xFF)
                    return ImageInfo("JPEG", w, h)
                }
                i += 2 + len
            }
            return ImageInfo("JPEG", 0, 0)
        }
        if (bytes.size >= 30 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WEBP") {
            val chunk = String(bytes, 12, 4)
            return when (chunk) {
                "VP8X" -> ImageInfo("WEBP", 1 + le24(bytes, 24), 1 + le24(bytes, 27))
                "VP8L" -> {
                    val b = ByteBuffer.wrap(bytes, 21, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    ImageInfo("WEBP", 1 + (b and 0x3FFF), 1 + ((b shr 14) and 0x3FFF))
                }
                else -> ImageInfo("WEBP", (le16(bytes, 26) and 0x3FFF), (le16(bytes, 28) and 0x3FFF))
            }
        }
        if (bytes.size > 10 && String(bytes, 0, 6).startsWith("GIF8")) return ImageInfo("GIF", le16(bytes, 6), le16(bytes, 8))
        val head = String(bytes, 0, minOf(bytes.size, 512), Charsets.UTF_8)
        if (head.contains("<svg")) {
            val w = Regex("width=\"(\\d+)").find(head)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val h = Regex("height=\"(\\d+)").find(head)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            return ImageInfo("SVG", w, h)
        }
        return null
    }

    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun le24(b: ByteArray, o: Int) = le16(b, o) or ((b[o + 2].toInt() and 0xFF) shl 16)
}

/** Lists project assets with metadata and detects unused ones (never deletes anything automatically). */
object AssetScanner {
    private val ASSET_KINDS = setOf(FileKind.IMAGE, FileKind.MODEL3D, FileKind.AUDIO)

    fun scan(fs: ProjectFileSystem, withMetadata: Boolean = true): List<AssetEntry> {
        val all = fs.walkFiles().toList()
        val assets = all.filter { FileKind.of(it.name) in ASSET_KINDS || (it.path.startsWith("assets/") && FileKind.of(it.name) == FileKind.JSON) }
        val sources = all.filter { val k = FileKind.of(it.name); k.isText && k != FileKind.JSON || it.name.endsWith(".gltf") }
            .filter { it.size < 2_000_000 && !it.name.endsWith(".min.js") }
            .mapNotNull { runCatching { fs.readText(it.path) }.getOrNull() }
        val corpus = sources.joinToString("\n")
        return assets.map { node ->
            val fileName = node.name
            val referenced = corpus.contains(node.path) || corpus.contains(fileName)
            val kind = FileKind.of(fileName)
            val file = fs.file(node.path)
            AssetEntry(
                path = node.path, kind = kind, size = node.size, referenced = referenced,
                image = if (withMetadata && kind == FileKind.IMAGE && node.size < 30_000_000) runCatching { ImageInspector.inspect(file.readBytes()) }.getOrNull() else null,
                model = if (withMetadata && kind == FileKind.MODEL3D && node.size < 100_000_000) runCatching { ModelInspector.inspect(file) }.getOrElse { ModelInfo(file.extension, false, it.message) } else null,
            )
        }.sortedBy { it.path }
    }

    fun unused(entries: List<AssetEntry>) = entries.filter { !it.referenced }
}

/** Procedural 3D asset generator: emits an ES module of Three.js factory functions (no AI required). */
object ProceduralAssets {
    val KINDS = listOf("crate", "tree", "rock", "platform", "enemy", "portal", "coin", "projectile")

    private val FACTORIES = mapOf(
        "crate" to """
            export function createCrate(size = 1, color = 0x9c6b3c) {
              const g = new THREE.Group();
              const box = new THREE.Mesh(new THREE.BoxGeometry(size, size, size), new THREE.MeshStandardMaterial({ color, roughness: 0.8 }));
              const edge = new THREE.MeshStandardMaterial({ color: 0x5a3a1c });
              for (const y of [-0.45, 0.45]) for (const z of [-0.45, 0.45]) {
                const bar = new THREE.Mesh(new THREE.BoxGeometry(size * 1.02, size * 0.1, size * 0.1), edge);
                bar.position.set(0, y * size, z * size); g.add(bar);
              }
              g.add(box); g.userData.kind = 'crate'; return g;
            }
        """,
        "tree" to """
            export function createTree(height = 3, leafColor = 0x2f6b34) {
              const g = new THREE.Group();
              const trunk = new THREE.Mesh(new THREE.CylinderGeometry(0.15 * height / 3, 0.22 * height / 3, height * 0.45, 8), new THREE.MeshStandardMaterial({ color: 0x6b4a2b }));
              trunk.position.y = height * 0.225;
              const leaves = new THREE.Mesh(new THREE.ConeGeometry(height * 0.4, height * 0.75, 9), new THREE.MeshStandardMaterial({ color: leafColor }));
              leaves.position.y = height * 0.75;
              g.add(trunk, leaves); g.userData.kind = 'tree'; return g;
            }
        """,
        "rock" to """
            export function createRock(radius = 0.8, seed = 1) {
              const geo = new THREE.DodecahedronGeometry(radius, 1);
              const pos = geo.attributes.position;
              let s = seed * 9301 + 49297;
              for (let i = 0; i < pos.count; i++) {
                s = (s * 9301 + 49297) % 233280;
                const k = 0.85 + (s / 233280) * 0.3;
                pos.setXYZ(i, pos.getX(i) * k, pos.getY(i) * k * 0.8, pos.getZ(i) * k);
              }
              geo.computeVertexNormals();
              const m = new THREE.Mesh(geo, new THREE.MeshStandardMaterial({ color: 0x8a8f99, flatShading: true }));
              m.userData.kind = 'rock'; return m;
            }
        """,
        "platform" to """
            export function createPlatform(width = 4, depth = 4, height = 0.5, color = 0x4c566a) {
              const m = new THREE.Mesh(new THREE.BoxGeometry(width, height, depth), new THREE.MeshStandardMaterial({ color }));
              m.userData.kind = 'platform'; return m;
            }
        """,
        "enemy" to """
            export function createSimpleEnemy(color = 0xff4d6d) {
              const g = new THREE.Group();
              const body = new THREE.Mesh(new THREE.SphereGeometry(0.6, 16, 12), new THREE.MeshStandardMaterial({ color }));
              body.position.y = 0.6;
              const eyeMat = new THREE.MeshStandardMaterial({ color: 0xffffff, emissive: 0x333333 });
              for (const x of [-0.2, 0.2]) { const e = new THREE.Mesh(new THREE.SphereGeometry(0.12, 8, 6), eyeMat); e.position.set(x, 0.8, 0.5); g.add(e); }
              const spike = new THREE.Mesh(new THREE.ConeGeometry(0.2, 0.5, 6), new THREE.MeshStandardMaterial({ color: 0x222222 }));
              spike.position.y = 1.3;
              g.add(body, spike); g.userData.kind = 'enemy'; return g;
            }
        """,
        "portal" to """
            export function createPortal(radius = 1.2, color = 0x7c5cff) {
              const g = new THREE.Group();
              const ring = new THREE.Mesh(new THREE.TorusGeometry(radius, 0.12, 12, 48), new THREE.MeshStandardMaterial({ color, emissive: color, emissiveIntensity: 0.6 }));
              const core = new THREE.Mesh(new THREE.CircleGeometry(radius * 0.92, 40), new THREE.MeshBasicMaterial({ color, transparent: true, opacity: 0.35, side: THREE.DoubleSide }));
              g.add(ring, core); g.position.y = radius + 0.2; g.userData.kind = 'portal';
              g.userData.update = (t) => { core.material.opacity = 0.25 + Math.sin(t * 3) * 0.1; ring.rotation.z = t * 0.5; };
              return g;
            }
        """,
        "coin" to """
            export function createCoin(radius = 0.35) {
              const m = new THREE.Mesh(new THREE.CylinderGeometry(radius, radius, 0.08, 24), new THREE.MeshStandardMaterial({ color: 0xffd34d, metalness: 0.7, roughness: 0.3 }));
              m.rotation.x = Math.PI / 2; m.userData.kind = 'coin';
              m.userData.update = (t) => { m.rotation.z = t * 2; };
              return m;
            }
        """,
        "projectile" to """
            export function createProjectile(color = 0x57e3ff) {
              const m = new THREE.Mesh(new THREE.SphereGeometry(0.15, 10, 8), new THREE.MeshStandardMaterial({ color, emissive: color, emissiveIntensity: 0.8 }));
              m.userData.kind = 'projectile'; m.userData.velocity = new THREE.Vector3();
              return m;
            }
        """,
    )

    /** Generates `assets/procedural/<kind>.js`-style module source for the requested kinds. */
    fun module(kinds: Collection<String>): String {
        val unknown = kinds.filter { it !in FACTORIES }
        if (unknown.isNotEmpty()) throw IllegalArgumentException("Unknown procedural asset(s): ${unknown.joinToString()}")
        return buildString {
            append("// Procedural 3D assets generated by FOLD FORGE (Three.js geometry only, no external files).\n")
            append("import * as THREE from 'three';\n")
            kinds.distinct().forEach { append(FACTORIES.getValue(it).trimIndent()).append("\n\n") }
        }
    }
}

// ---------------------------------------------------------------- external generation providers

data class GeneratedAsset(val fileName: String, val bytes: ByteArray, val mimeType: String)

sealed class AssetJobStatus {
    data class Done(val asset: GeneratedAsset) : AssetJobStatus()
    data class Running(val progress: Int?) : AssetJobStatus()
    data class Failed(val reason: String) : AssetJobStatus()
}

/**
 * Architecture for external AI asset generation. Implementations must call a real, user-configured
 * service; if a capability is not offered by a provider it throws [UnsupportedOperationException].
 */
interface AssetGenerationProvider {
    val name: String
    suspend fun generateImage(prompt: String, size: String = "1024x1024"): GeneratedAsset
    suspend fun generateTexture(prompt: String): GeneratedAsset
    suspend fun generateModel(prompt: String): String // job id
    suspend fun getStatus(jobId: String): AssetJobStatus
    suspend fun downloadAsset(jobId: String): GeneratedAsset
}

class AssetGenerationException(message: String) : IOException(message)
