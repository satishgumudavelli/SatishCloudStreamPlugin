package com.movies4u

data class ZipFileEntry(
    val name: String,
    val method: Int,
    val compSize: Long,
    val uncompSize: Long,
    val localHeaderOffset: Long,
)

// Minimal PKZIP structural parsing - just enough to locate each entry's compressed data inside
// a remote zip via HTTP Range requests, without ever downloading the whole archive.
object ZipCentralDirectory {
    private val EOCD_SIG = byteArrayOf(0x50, 0x4B, 0x05, 0x06)
    private val CDH_SIG = byteArrayOf(0x50, 0x4B, 0x01, 0x02)

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or
            ((b[o + 1].toLong() and 0xFF) shl 8) or
            ((b[o + 2].toLong() and 0xFF) shl 16) or
            ((b[o + 3].toLong() and 0xFF) shl 24)

    private fun findEocd(tail: ByteArray): Int {
        for (i in tail.size - 22 downTo 0) {
            if (tail[i] == EOCD_SIG[0] && tail[i + 1] == EOCD_SIG[1] && tail[i + 2] == EOCD_SIG[2] && tail[i + 3] == EOCD_SIG[3]) {
                return i
            }
        }
        return -1
    }

    // `tail` is the last `tailStartInFile.until(fileSize)` bytes of the archive. Returns null if
    // the tail window doesn't reach far enough back to contain both the EOCD record and the full
    // central directory - the caller should retry with a bigger window in that case.
    fun parseEntries(tail: ByteArray, tailStartInFile: Long): List<ZipFileEntry>? {
        val eocdIdx = findEocd(tail)
        if (eocdIdx == -1) return null

        val totalEntries = u16(tail, eocdIdx + 10)
        val cdOffset = u32(tail, eocdIdx + 16)
        val cdStartInTail = cdOffset - tailStartInFile
        if (cdStartInTail < 0 || cdStartInTail > Int.MAX_VALUE) return null

        val entries = mutableListOf<ZipFileEntry>()
        var pos = cdStartInTail.toInt()
        repeat(totalEntries) {
            if (pos + 46 > tail.size) return null
            if (tail[pos] != CDH_SIG[0] || tail[pos + 1] != CDH_SIG[1] || tail[pos + 2] != CDH_SIG[2] || tail[pos + 3] != CDH_SIG[3]) {
                return null
            }
            val method = u16(tail, pos + 10)
            val compSize = u32(tail, pos + 20)
            val uncompSize = u32(tail, pos + 24)
            val fnLen = u16(tail, pos + 28)
            val extraLen = u16(tail, pos + 30)
            val commentLen = u16(tail, pos + 32)
            val relOffset = u32(tail, pos + 42)
            if (pos + 46 + fnLen > tail.size) return null
            val name = String(tail, pos + 46, fnLen, Charsets.UTF_8)
            entries.add(ZipFileEntry(name, method, compSize, uncompSize, relOffset))
            pos += 46 + fnLen + extraLen + commentLen
        }
        return entries
    }

    // The central directory's filename/extra-field lengths aren't guaranteed to match the local
    // file header's - read the local header itself to get the true data start offset.
    fun localDataOffset(localHeaderBytes: ByteArray, localHeaderOffset: Long): Long {
        val fnLen = u16(localHeaderBytes, 26)
        val extraLen = u16(localHeaderBytes, 28)
        return localHeaderOffset + 30 + fnLen + extraLen
    }
}
