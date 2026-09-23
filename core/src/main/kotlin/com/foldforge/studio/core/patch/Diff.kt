package com.foldforge.studio.core.patch

enum class DiffOp { EQUAL, INSERT, DELETE }

data class DiffLine(val op: DiffOp, val text: String, val oldLine: Int?, val newLine: Int?)

data class DiffHunk(val oldStart: Int, val oldCount: Int, val newStart: Int, val newCount: Int, val lines: List<DiffLine>)

/** Line-based Myers diff with unified-diff rendering. */
object Diff {

    fun lines(text: String): List<String> = if (text.isEmpty()) emptyList() else text.removeSuffix("\n").split('\n')

    fun diffLines(a: List<String>, b: List<String>): List<DiffLine> {
        // Trim common prefix/suffix for speed.
        var prefix = 0
        while (prefix < a.size && prefix < b.size && a[prefix] == b[prefix]) prefix++
        var suffix = 0
        while (suffix < a.size - prefix && suffix < b.size - prefix && a[a.size - 1 - suffix] == b[b.size - 1 - suffix]) suffix++
        val aMid = a.subList(prefix, a.size - suffix)
        val bMid = b.subList(prefix, b.size - suffix)
        val out = ArrayList<DiffLine>()
        for (i in 0 until prefix) out += DiffLine(DiffOp.EQUAL, a[i], i + 1, i + 1)
        val mid = myers(aMid, bMid)
        var ai = prefix
        var bi = prefix
        for (op in mid) {
            when (op) {
                DiffOp.EQUAL -> { out += DiffLine(DiffOp.EQUAL, a[ai], ai + 1, bi + 1); ai++; bi++ }
                DiffOp.DELETE -> { out += DiffLine(DiffOp.DELETE, a[ai], ai + 1, null); ai++ }
                DiffOp.INSERT -> { out += DiffLine(DiffOp.INSERT, b[bi], null, bi + 1); bi++ }
            }
        }
        for (k in 0 until suffix) {
            out += DiffLine(DiffOp.EQUAL, a[ai], ai + 1, bi + 1); ai++; bi++
        }
        return out
    }

    private fun myers(a: List<String>, b: List<String>): List<DiffOp> {
        val n = a.size
        val m = b.size
        if (n == 0) return List(m) { DiffOp.INSERT }
        if (m == 0) return List(n) { DiffOp.DELETE }
        val max = n + m
        val offset = max
        var v = IntArray(2 * max + 2)
        val trace = ArrayList<IntArray>()
        var found = false
        for (d in 0..max) {
            trace += v.copyOf()
            var k = -d
            while (k <= d) {
                var x = if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) v[offset + k + 1] else v[offset + k - 1] + 1
                var y = x - k
                while (x < n && y < m && a[x] == b[y]) { x++; y++ }
                v[offset + k] = x
                if (x >= n && y >= m) { found = true; break }
                k += 2
            }
            if (found) { trace += v.copyOf(); break }
        }
        // Backtrack
        val ops = ArrayList<DiffOp>()
        var x = n
        var y = m
        for (d in trace.size - 2 downTo 0) {
            val vd = trace[d]
            val k = x - y
            val prevK = if (k == -d || (k != d && vd[offset + k - 1] < vd[offset + k + 1])) k + 1 else k - 1
            val prevX = vd[offset + prevK]
            val prevY = prevX - prevK
            while (x > prevX && y > prevY) { ops += DiffOp.EQUAL; x--; y-- }
            if (d > 0) {
                if (x == prevX) { ops += DiffOp.INSERT; y-- } else { ops += DiffOp.DELETE; x-- }
            }
        }
        while (x > 0 && y > 0) { ops += DiffOp.EQUAL; x--; y-- }
        ops.reverse()
        return ops
    }

    fun hunks(diff: List<DiffLine>, context: Int = 3): List<DiffHunk> {
        val changed = diff.indices.filter { diff[it].op != DiffOp.EQUAL }
        if (changed.isEmpty()) return emptyList()
        val ranges = ArrayList<IntRange>()
        var start = (changed.first() - context).coerceAtLeast(0)
        var end = (changed.first() + context).coerceAtMost(diff.size - 1)
        for (i in changed.drop(1)) {
            if (i - context <= end + 1) end = (i + context).coerceAtMost(diff.size - 1)
            else { ranges += start..end; start = (i - context).coerceAtLeast(0); end = (i + context).coerceAtMost(diff.size - 1) }
        }
        ranges += start..end
        return ranges.map { r ->
            val lines = diff.subList(r.first, r.last + 1)
            val oldStart = lines.firstNotNullOfOrNull { it.oldLine } ?: (diff.take(r.first).lastOrNull { it.oldLine != null }?.oldLine ?: 0)
            val newStart = lines.firstNotNullOfOrNull { it.newLine } ?: (diff.take(r.first).lastOrNull { it.newLine != null }?.newLine ?: 0)
            DiffHunk(oldStart, lines.count { it.op != DiffOp.INSERT }, newStart, lines.count { it.op != DiffOp.DELETE }, lines)
        }
    }

    fun unified(path: String, before: String?, after: String?, context: Int = 3): String {
        val d = diffLines(lines(before ?: ""), lines(after ?: ""))
        val sb = StringBuilder()
        sb.append("--- ").append(if (before == null) "/dev/null" else "a/$path").append('\n')
        sb.append("+++ ").append(if (after == null) "/dev/null" else "b/$path").append('\n')
        for (h in hunks(d, context)) {
            sb.append("@@ -${h.oldStart},${h.oldCount} +${h.newStart},${h.newCount} @@\n")
            for (l in h.lines) sb.append(when (l.op) { DiffOp.EQUAL -> ' '; DiffOp.INSERT -> '+'; DiffOp.DELETE -> '-' }).append(l.text).append('\n')
        }
        return sb.toString()
    }

    fun stats(before: String?, after: String?): Pair<Int, Int> {
        val d = diffLines(lines(before ?: ""), lines(after ?: ""))
        return d.count { it.op == DiffOp.INSERT } to d.count { it.op == DiffOp.DELETE }
    }
}
