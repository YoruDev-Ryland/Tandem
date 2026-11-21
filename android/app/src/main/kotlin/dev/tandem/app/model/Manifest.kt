package dev.tandem.app.model

import kotlinx.serialization.Serializable

@Serializable
data class ManifestEntry(val path: String, val hash: String, val size: Long)

object Manifests {
    const val MAX_ENTRIES = 10000

    fun build(fs: dev.tandem.app.fs.Fs, baseDir: String, files: List<String>): List<ManifestEntry> {
        for (rel in files) {
            require(!rel.startsWith("/") && !rel.startsWith("\\") && rel.split('/', '\\').none { it == ".." }) {
                "unsafe manifest path: $rel"
            }
        }
        return files.map { rel ->
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(64 * 1024)
            var size = 0L
            fs.openRead("$baseDir/$rel").use { ins ->
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                    size += n
                }
            }
            ManifestEntry(rel, hex(md.digest()), size)
        }.sortedBy { it.path }
    }

    /** Mirrors the server: ordinal sort, SHA-256 over `path\0hash\0size\n`, lowercase hex. */
    fun hash(entries: List<ManifestEntry>): String {
        val sb = StringBuilder()
        for (e in entries.sortedBy { it.path }) {
            sb.append(e.path).append('\u0000').append(e.hash).append('\u0000').append(e.size).append('\n')
        }
        return hex(java.security.MessageDigest.getInstance("SHA-256").digest(sb.toString().toByteArray(Charsets.UTF_8)))
    }

    /** The server rejects empty manifests (400 bad_manifest) and caps the entry count. */
    fun isUploadable(m: List<ManifestEntry>) = m.isNotEmpty() && m.size <= MAX_ENTRIES

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
