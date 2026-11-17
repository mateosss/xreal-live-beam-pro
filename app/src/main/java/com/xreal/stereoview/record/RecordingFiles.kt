package com.xreal.stereoview.record

import java.io.File
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Operations on saved recordings (a folder, or a .zip of one): zip/unzip replacing the
 * original, copying, sizes. Plain java.io, so it is unit-tested on the JVM. Progress is
 * reported as (bytes done, bytes total).
 */
object RecordingFiles {
    /** Already-compressed files are stored as they are: zipping them again only costs time. */
    private val STORED_EXTENSIONS = setOf("png", "jpg", "jpeg", "mp4", "mkv", "zip")

    /** File that keeps media scanners out of a folder (gallery apps, MediaStore). */
    const val NO_MEDIA = ".nomedia"

    fun size(f: File): Long = if (f.isDirectory) f.walkTopDown().filter { it.isFile }.sumOf { it.length() } else f.length()

    /**
     * Zips folder [dir] into `<dir>.zip` beside it (entries under `<dir name>/`), then deletes
     * the folder. Writes to a temporary file first, so an interruption never loses data.
     */
    fun zip(dir: File, progress: (Long, Long) -> Unit = { _, _ -> }): File {
        val target = File(dir.parentFile, "${dir.name}.zip")
        if (target.exists()) throw IOException("${target.name} already exists")
        val partial = File(dir.parentFile, "${dir.name}.zip.part")
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        val total = files.sumOf { it.length() }
        var done = 0L
        val buffer = ByteArray(1 shl 16)
        try {
            ZipOutputStream(partial.outputStream().buffered(1 shl 20)).use { zip ->
                zip.setLevel(1) // CSV/YAML/JSON compress well even at the fastest level
                for (f in files) {
                    val name = dir.name + "/" + f.relativeTo(dir).invariantSeparatorsPath
                    val entry = ZipEntry(name).apply { time = f.lastModified() }
                    if (f.extension.lowercase() in STORED_EXTENSIONS) {
                        // STORED entries need size and CRC up front.
                        entry.method = ZipEntry.STORED
                        entry.size = f.length()
                        entry.compressedSize = f.length()
                        entry.crc = crcOf(f, buffer)
                    }
                    zip.putNextEntry(entry)
                    f.inputStream().use { input ->
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            zip.write(buffer, 0, n)
                            done += n
                        }
                    }
                    zip.closeEntry()
                    progress(done, total)
                }
            }
            if (!partial.renameTo(target)) throw IOException("Cannot rename ${partial.name}")
        } catch (e: Exception) {
            partial.delete()
            throw e
        }
        dir.deleteRecursively()
        return target
    }

    /** Extracts [zip] beside it, then deletes it. Returns the recording folder. */
    fun unzip(zip: File, progress: (Long, Long) -> Unit = { _, _ -> }): File {
        val parent = zip.parentFile!!
        val name = zip.name.removeSuffix(".zip")
        val target = File(parent, name)
        if (target.exists()) throw IOException("$name already exists")
        val staging = File(parent, "$name.unzipping")
        staging.deleteRecursively()
        val total = zip.length()
        val buffer = ByteArray(1 shl 16)
        try {
            val counting = CountingInput(zip.inputStream().buffered(1 shl 20))
            ZipInputStream(counting).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    // Entries are "<name>/…"; strip the top folder whatever it is called.
                    val relative = entry.name.substringAfter('/', entry.name)
                    if (relative.isEmpty()) continue
                    val out = File(staging, relative)
                    if (!out.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                        throw IOException("Unsafe path in zip: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { o ->
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                o.write(buffer, 0, n)
                            }
                        }
                        if (entry.time > 0) out.setLastModified(entry.time)
                    }
                    progress(counting.count, total)
                }
            }
            if (!staging.renameTo(target)) throw IOException("Cannot rename ${staging.name}")
        } catch (e: Exception) {
            staging.deleteRecursively()
            throw e
        }
        zip.delete()
        return target
    }

    /**
     * Copies a recording (folder or zip) into [destParent]. A copied folder gets a [NO_MEDIA]
     * file so its thousands of images stay out of galleries and the media index.
     */
    fun copy(source: File, destParent: File, progress: (Long, Long) -> Unit = { _, _ -> }): File {
        destParent.mkdirs()
        val target = File(destParent, source.name)
        if (target.exists()) throw IOException("${target.path} already exists")
        val total = size(source)
        var done = 0L
        val buffer = ByteArray(1 shl 16)
        fun copyFile(from: File, to: File) {
            to.parentFile?.mkdirs()
            from.inputStream().use { i ->
                to.outputStream().use { o ->
                    while (true) {
                        val n = i.read(buffer)
                        if (n < 0) break
                        o.write(buffer, 0, n)
                        done += n
                    }
                }
            }
            progress(done, total)
        }
        try {
            if (source.isDirectory) {
                target.mkdirs()
                File(target, NO_MEDIA).createNewFile()
                source.walkTopDown().filter { it.isFile }.forEach { copyFile(it, File(target, it.relativeTo(source).path)) }
            } else {
                copyFile(source, target)
            }
        } catch (e: Exception) {
            target.deleteRecursively()
            throw e
        }
        return target
    }

    private fun crcOf(f: File, buffer: ByteArray): Long {
        val crc = CRC32()
        f.inputStream().use { i ->
            while (true) {
                val n = i.read(buffer)
                if (n < 0) break
                crc.update(buffer, 0, n)
            }
        }
        return crc.value
    }

    private class CountingInput(private val inner: java.io.InputStream) : java.io.FilterInputStream(inner) {
        var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
    }
}
