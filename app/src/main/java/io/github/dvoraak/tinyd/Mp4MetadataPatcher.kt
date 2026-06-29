package io.github.dvoraak.tinyd

import java.io.File
import java.io.RandomAccessFile

/**
 * Hand-rolled patcher that injects camera metadata (GPS, creation time) into an
 * already-muxed MP4 by editing top-level ISO BMFF boxes in place.
 *
 * Media3's Transformer drops the source's GPS udta/©xyz atom across a transcode,
 * and rewrites mvhd/tkhd creation_time to "now". For an archive workflow that's
 * the wrong default, so we patch the output file before it leaves the cache.
 *
 * The MP4 boxes we touch:
 *   - moov / mvhd  → creation_time + modification_time (seconds since 1904-01-01)
 *   - moov / udta / ©xyz → ISO-6709 location string, in the QuickTime
 *     length-prefixed-string layout that Android Camera/Google Photos read.
 *
 * The output ends up with moov sitting after mdat (faststart=false), which Media3
 * does by default. When that's true, growing moov is a pure append at EOF — no
 * mdat offsets shift, no stco/co64 rewriting needed. If a future Media3 version
 * flips this and writes moov first, this patcher must move mdat or it will
 * silently corrupt playback.
 */
object Mp4MetadataPatcher {

    /** Seconds between MP4 epoch (1904-01-01 UTC) and Unix epoch (1970-01-01 UTC). */
    private const val MP4_EPOCH_OFFSET_SECONDS = 2_082_844_800L

    fun patch(file: File, dateTakenMs: Long, iso6709Location: String?) {
        if (!file.exists() || file.length() < 16) return
        if (dateTakenMs <= 0 && iso6709Location.isNullOrBlank()) return
        try {
            RandomAccessFile(file, "rw").use { raf ->
                val topLevel = readTopLevelBoxes(raf)
                val moov = topLevel.firstOrNull { it.type == "moov" } ?: return
                if (moov.payloadEndExclusive != raf.length()) {
                    // moov is not the last box; growing it would shift mdat and break offsets.
                    // Bail out rather than corrupt the file. (Media3 1.5.0 puts moov last.)
                    // We can still patch mvhd in place (same size) below — only the GPS append
                    // would be unsafe, so degrade gracefully.
                    if (dateTakenMs > 0) patchMvhdCreationTime(raf, moov, dateTakenMs)
                    return
                }
                if (dateTakenMs > 0) patchMvhdCreationTime(raf, moov, dateTakenMs)
                if (!iso6709Location.isNullOrBlank()) appendLocationToUdta(raf, moov, iso6709Location)
            }
        } catch (e: Exception) {
            // Best-effort only — never break the saved file because of metadata
            e.printStackTrace()
        }
    }

    private data class BoxRef(
        val type: String,
        val headerStart: Long,    // offset of the size word
        val payloadStart: Long,   // offset just after the type word (and largesize if present)
        val payloadEndExclusive: Long,
        val isLargeSize: Boolean,
    ) {
        val size: Long get() = payloadEndExclusive - headerStart
    }

    private fun readTopLevelBoxes(raf: RandomAccessFile): List<BoxRef> {
        val boxes = mutableListOf<BoxRef>()
        var pos = 0L
        val total = raf.length()
        while (pos + 8 <= total) {
            raf.seek(pos)
            val size32 = raf.readInt().toLong() and 0xFFFFFFFFL
            val typeBytes = ByteArray(4).also { raf.readFully(it) }
            val type = String(typeBytes, Charsets.US_ASCII)
            val (payloadStart, end, large) = when {
                size32 == 1L -> {
                    val large = raf.readLong()
                    Triple(pos + 16, pos + large, true)
                }
                size32 == 0L -> Triple(pos + 8, total, false) // "to EOF"
                else -> Triple(pos + 8, pos + size32, false)
            }
            boxes.add(BoxRef(type, pos, payloadStart, end, large))
            if (end <= pos) break
            pos = end
        }
        return boxes
    }

    /** Walk children of a container box (moov, udta, ...). */
    private fun readChildren(raf: RandomAccessFile, container: BoxRef): List<BoxRef> {
        val boxes = mutableListOf<BoxRef>()
        var pos = container.payloadStart
        while (pos + 8 <= container.payloadEndExclusive) {
            raf.seek(pos)
            val size32 = raf.readInt().toLong() and 0xFFFFFFFFL
            val typeBytes = ByteArray(4).also { raf.readFully(it) }
            val type = String(typeBytes, Charsets.US_ASCII)
            val (payloadStart, end, large) = when {
                size32 == 1L -> {
                    val large = raf.readLong()
                    Triple(pos + 16, pos + large, true)
                }
                size32 == 0L -> Triple(pos + 8, container.payloadEndExclusive, false)
                else -> Triple(pos + 8, pos + size32, false)
            }
            boxes.add(BoxRef(type, pos, payloadStart, end, large))
            if (end <= pos) break
            pos = end
        }
        return boxes
    }

    private fun patchMvhdCreationTime(raf: RandomAccessFile, moov: BoxRef, dateTakenMs: Long) {
        val mvhd = readChildren(raf, moov).firstOrNull { it.type == "mvhd" } ?: return
        // mvhd layout: version(1) + flags(3) + creation_time + modification_time + timescale + duration ...
        raf.seek(mvhd.payloadStart)
        val version = raf.readUnsignedByte()
        val mp4Time = (dateTakenMs / 1000) + MP4_EPOCH_OFFSET_SECONDS
        raf.seek(mvhd.payloadStart + 4) // skip version+flags
        if (version == 1) {
            raf.writeLong(mp4Time)
            raf.writeLong(mp4Time)
        } else {
            raf.writeInt(mp4Time.toInt())
            raf.writeInt(mp4Time.toInt())
        }

        // Also patch each track's tkhd creation/modification for consistency
        readChildren(raf, moov)
            .filter { it.type == "trak" }
            .forEach { trak ->
                val tkhd = readChildren(raf, trak).firstOrNull { it.type == "tkhd" } ?: return@forEach
                raf.seek(tkhd.payloadStart)
                val v = raf.readUnsignedByte()
                raf.seek(tkhd.payloadStart + 4)
                if (v == 1) {
                    raf.writeLong(mp4Time)
                    raf.writeLong(mp4Time)
                } else {
                    raf.writeInt(mp4Time.toInt())
                    raf.writeInt(mp4Time.toInt())
                }
            }
    }

    private fun appendLocationToUdta(raf: RandomAccessFile, moov: BoxRef, iso6709: String) {
        val children = readChildren(raf, moov)
        val existingUdta = children.firstOrNull { it.type == "udta" }

        // QuickTime ©xyz payload: 2-byte big-endian length of UTF-8 string, 2-byte language,
        // then the ISO-6709 bytes. Google Photos and Android Camera read this layout.
        val payload = iso6709.toByteArray(Charsets.UTF_8)
        val xyzPayload = ByteArray(payload.size + 4)
        xyzPayload[0] = ((payload.size shr 8) and 0xFF).toByte()
        xyzPayload[1] = (payload.size and 0xFF).toByte()
        // Language: packed ISO-639-2/T 5-bit per char of "und" = 0x55C4
        xyzPayload[2] = 0x55.toByte()
        xyzPayload[3] = 0xC4.toByte()
        System.arraycopy(payload, 0, xyzPayload, 4, payload.size)

        val xyzBox = buildBox(byteArrayOf(0xA9.toByte(), 'x'.code.toByte(), 'y'.code.toByte(), 'z'.code.toByte()), xyzPayload)

        if (existingUdta == null) {
            // Append a brand new udta at the end of moov.
            // Strategy: write the udta past the end of file, then grow moov size to include it.
            val newUdta = buildBox("udta".toByteArray(Charsets.US_ASCII), xyzBox)
            raf.seek(raf.length())
            raf.write(newUdta)
            growBox(raf, moov, newUdta.size.toLong())
        } else {
            // Insert xyz at the end of udta — only safe if udta is the last child of moov AND moov is last in file.
            if (existingUdta.payloadEndExclusive != moov.payloadEndExclusive) {
                // udta isn't last: we'd have to shift bytes. Append a second udta instead — readers ignore duplicates' contents but it's safer to merge.
                // Simpler: write a fresh udta with xyz appended after existing moov content.
                val newUdta = buildBox("udta".toByteArray(Charsets.US_ASCII), xyzBox)
                raf.seek(raf.length())
                raf.write(newUdta)
                growBox(raf, moov, newUdta.size.toLong())
                return
            }
            // udta is last child of moov, and moov is last in file — append xyz at EOF, grow udta + moov.
            raf.seek(raf.length())
            raf.write(xyzBox)
            growBox(raf, existingUdta, xyzBox.size.toLong())
            growBox(raf, moov, xyzBox.size.toLong())
        }
    }

    private fun buildBox(type: ByteArray, payload: ByteArray): ByteArray {
        val totalSize = 8 + payload.size
        val out = ByteArray(totalSize)
        out[0] = ((totalSize ushr 24) and 0xFF).toByte()
        out[1] = ((totalSize ushr 16) and 0xFF).toByte()
        out[2] = ((totalSize ushr 8) and 0xFF).toByte()
        out[3] = (totalSize and 0xFF).toByte()
        System.arraycopy(type, 0, out, 4, 4)
        System.arraycopy(payload, 0, out, 8, payload.size)
        return out
    }

    /** Add [delta] bytes to a box's size field. Assumes 32-bit size (size32 != 1). */
    private fun growBox(raf: RandomAccessFile, box: BoxRef, delta: Long) {
        if (box.isLargeSize) {
            raf.seek(box.headerStart + 8)
            val current = raf.readLong()
            raf.seek(box.headerStart + 8)
            raf.writeLong(current + delta)
        } else {
            raf.seek(box.headerStart)
            val current = raf.readInt().toLong() and 0xFFFFFFFFL
            val newSize = current + delta
            raf.seek(box.headerStart)
            raf.writeInt(newSize.toInt())
        }
    }

    /**
     * Extracts the source ISO-6709 location string from an mp4 (Pixel/Camera-style udta/©xyz),
     * if present. Returns null when the box doesn't exist or fails to parse.
     */
    fun readSourceLocation(file: File): String? {
        if (!file.exists()) return null
        return try {
            RandomAccessFile(file, "r").use { raf -> findXyzInFile(raf) }
        } catch (e: Exception) {
            null
        }
    }

    private fun findXyzInFile(raf: RandomAccessFile): String? {
        val top = readTopLevelBoxes(raf)
        val moov = top.firstOrNull { it.type == "moov" } ?: return null
        val udta = readChildren(raf, moov).firstOrNull { it.type == "udta" } ?: return null
        val xyz = readChildren(raf, udta).firstOrNull {
            it.type.length == 4 && it.type[0].code == 0xA9 && it.type.substring(1) == "xyz"
        } ?: return null
        raf.seek(xyz.payloadStart)
        val len = raf.readUnsignedShort()
        raf.readUnsignedShort() // language
        val bytes = ByteArray(len)
        raf.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }
}
