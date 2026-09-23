package com.foldforge.studio.core.apk

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AxmlException(message: String) : Exception(message)

/**
 * Reader/editor for Android binary XML (compiled AndroidManifest.xml).
 *
 * Edits never renumber existing strings: new values are appended to the string pool (after the
 * resource-ID-mapped attribute names), and the attribute is re-pointed at them. This keeps the
 * resource map and every other reference valid.
 */
class AxmlEditor(bytes: ByteArray) {
    private val strings = ArrayList<String>()
    private var utf8 = false
    private var poolFlags = 0
    private var styleCount = 0
    private var styleOffsets = IntArray(0)
    private var styleData = ByteArray(0)
    private var tree: ByteArray // everything after the string pool chunk

    data class Attribute(val element: String, val name: String, val namespace: String?, val type: Int, val data: Int, val stringValue: String?)

    init {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 16 || bb.getShort(0).toInt() != RES_XML_TYPE) throw AxmlException("Not a binary XML file")
        val headerSize = bb.getShort(2).toInt()
        val poolStart = headerSize
        if (bb.getShort(poolStart).toInt() != RES_STRING_POOL_TYPE) throw AxmlException("String pool not found")
        val poolHeaderSize = bb.getShort(poolStart + 2).toInt() and 0xFFFF
        val poolSize = bb.getInt(poolStart + 4)
        val stringCount = bb.getInt(poolStart + 8)
        styleCount = bb.getInt(poolStart + 12)
        poolFlags = bb.getInt(poolStart + 16)
        val stringsStart = bb.getInt(poolStart + 20)
        val stylesStart = bb.getInt(poolStart + 24)
        utf8 = poolFlags and UTF8_FLAG != 0
        val offsetsBase = poolStart + poolHeaderSize
        for (i in 0 until stringCount) {
            val off = poolStart + stringsStart + bb.getInt(offsetsBase + i * 4)
            strings += if (utf8) readUtf8(bytes, off) else readUtf16(bb, off)
        }
        styleOffsets = IntArray(styleCount) { bb.getInt(offsetsBase + stringCount * 4 + it * 4) }
        if (styleCount > 0) styleData = bytes.copyOfRange(poolStart + stylesStart, poolStart + poolSize)
        tree = bytes.copyOfRange(poolStart + poolSize, bytes.size)
    }

    val stringPool: List<String> get() = strings

    private fun readUtf8(b: ByteArray, start: Int): String {
        var p = start
        fun len(): Int {
            val first = b[p++].toInt() and 0xFF
            return if (first and 0x80 != 0) ((first and 0x7F) shl 8) or (b[p++].toInt() and 0xFF) else first
        }
        len() // utf16 length (unused)
        val byteLen = len()
        return String(b, p, byteLen, Charsets.UTF_8)
    }

    private fun readUtf16(bb: ByteBuffer, start: Int): String {
        var p = start
        var len = bb.getShort(p).toInt() and 0xFFFF
        p += 2
        if (len and 0x8000 != 0) {
            len = ((len and 0x7FFF) shl 16) or (bb.getShort(p).toInt() and 0xFFFF)
            p += 2
        }
        val chars = CharArray(len) { bb.getChar(p + it * 2) }
        return String(chars)
    }

    private fun str(idx: Int): String? = if (idx in strings.indices) strings[idx] else null

    /** Walks start-element chunks, calling [visit] with (elementName, attributeOffsetInTree) for each attribute. */
    private fun forEachAttribute(visit: (String, Int) -> Unit) {
        val bb = ByteBuffer.wrap(tree).order(ByteOrder.LITTLE_ENDIAN)
        var p = 0
        while (p + 8 <= tree.size) {
            val type = bb.getShort(p).toInt() and 0xFFFF
            val size = bb.getInt(p + 4)
            if (size <= 0 || p + size > tree.size) throw AxmlException("Corrupt XML chunk at $p")
            if (type == RES_XML_START_ELEMENT_TYPE) {
                val ext = p + 16
                val name = str(bb.getInt(ext + 4)) ?: ""
                val attrStart = bb.getShort(ext + 8).toInt() and 0xFFFF
                val attrSize = bb.getShort(ext + 10).toInt() and 0xFFFF
                val attrCount = bb.getShort(ext + 12).toInt() and 0xFFFF
                for (i in 0 until attrCount) visit(name, ext + attrStart + i * attrSize)
            }
            p += size
        }
    }

    fun attributes(): List<Attribute> {
        val out = ArrayList<Attribute>()
        val bb = ByteBuffer.wrap(tree).order(ByteOrder.LITTLE_ENDIAN)
        forEachAttribute { element, a ->
            val ns = bb.getInt(a)
            val name = str(bb.getInt(a + 4)) ?: ""
            val raw = bb.getInt(a + 8)
            val type = tree[a + 15].toInt() and 0xFF
            val data = bb.getInt(a + 16)
            out += Attribute(element, name, if (ns == -1) null else str(ns), type, data, if (type == TYPE_STRING) str(data) else str(raw))
        }
        return out
    }

    fun attribute(element: String, name: String): Attribute? = attributes().firstOrNull { it.element == element && it.name == name }

    /** Sets a string-valued attribute (converting references such as @string/app_name into a literal). */
    fun setString(element: String, name: String, value: String): Boolean {
        val idx = strings.size
        var found = false
        val bb = ByteBuffer.wrap(tree).order(ByteOrder.LITTLE_ENDIAN)
        forEachAttribute { el, a ->
            if (!found && el == element && str(bb.getInt(a + 4)) == name) {
                bb.putInt(a + 8, idx)
                bb.putShort(a + 12, 8)
                tree[a + 14] = 0
                tree[a + 15] = TYPE_STRING.toByte()
                bb.putInt(a + 16, idx)
                found = true
            }
        }
        if (found) strings += value
        return found
    }

    fun setInt(element: String, name: String, value: Int): Boolean {
        var found = false
        val bb = ByteBuffer.wrap(tree).order(ByteOrder.LITTLE_ENDIAN)
        forEachAttribute { el, a ->
            if (!found && el == element && str(bb.getInt(a + 4)) == name) {
                bb.putInt(a + 8, -1)
                tree[a + 15] = TYPE_INT_DEC.toByte()
                bb.putInt(a + 16, value)
                found = true
            }
        }
        return found
    }

    fun toByteArray(): ByteArray {
        val pool = encodePool()
        val total = 8 + pool.size + tree.size
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(RES_XML_TYPE.toShort()).putShort(8).putInt(total)
        out.put(pool).put(tree)
        return out.array()
    }

    private fun encodePool(): ByteArray {
        val data = ByteArrayOutputStream()
        val offsets = IntArray(strings.size)
        for ((i, s) in strings.withIndex()) {
            offsets[i] = data.size()
            if (utf8) {
                val bytes = s.toByteArray(Charsets.UTF_8)
                writeUtf8Len(data, s.length)
                writeUtf8Len(data, bytes.size)
                data.write(bytes)
                data.write(0)
            } else {
                val len = s.length
                if (len > 0x7FFF) {
                    writeLe16(data, 0x8000 or (len shr 16)); writeLe16(data, len and 0xFFFF)
                } else writeLe16(data, len)
                for (c in s) writeLe16(data, c.code)
                writeLe16(data, 0)
            }
        }
        while (data.size() % 4 != 0) data.write(0)
        val headerSize = 28
        val stringsStart = headerSize + strings.size * 4 + styleCount * 4
        val stylesStart = if (styleCount > 0) stringsStart + data.size() else 0
        val size = stringsStart + data.size() + styleData.size
        val bb = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        bb.putShort(RES_STRING_POOL_TYPE.toShort()).putShort(headerSize.toShort()).putInt(size)
        bb.putInt(strings.size).putInt(styleCount).putInt(poolFlags and SORTED_FLAG.inv())
        bb.putInt(stringsStart).putInt(stylesStart)
        offsets.forEach { bb.putInt(it) }
        styleOffsets.forEach { bb.putInt(it) }
        bb.put(data.toByteArray())
        bb.put(styleData)
        return bb.array()
    }

    private fun writeUtf8Len(out: ByteArrayOutputStream, len: Int) {
        if (len > 0x7F) { out.write(((len shr 8) and 0x7F) or 0x80); out.write(len and 0xFF) } else out.write(len)
    }

    private fun writeLe16(out: ByteArrayOutputStream, v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }

    companion object {
        const val RES_XML_TYPE = 0x0003
        const val RES_STRING_POOL_TYPE = 0x0001
        const val RES_XML_START_ELEMENT_TYPE = 0x0102
        const val UTF8_FLAG = 0x100
        const val SORTED_FLAG = 0x1
        const val TYPE_STRING = 0x03
        const val TYPE_INT_DEC = 0x10
        const val TYPE_REFERENCE = 0x01
    }
}
