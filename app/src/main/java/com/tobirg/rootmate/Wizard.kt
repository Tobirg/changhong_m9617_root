package com.tobirg.rootmate

import android.content.Context
import java.io.File

/**
 * The step-by-step flow, recognised from what is on the USB drive. Everything RootMate and the MBOOT scripts
 * make lives in the root of the drive (MBOOT can only write there, names up to 12 characters); the state is the
 * set of files, so it survives the data wipe that the flash step ends with.
 *
 * Markers: every MBOOT script writes a small <STEP>.OK file as its very last action before it resets, so a script
 * that stopped halfway is told apart from one that finished. The backup also leaves a .CRC file (the CRC32 MBOOT
 * computed from the eMMC data) next to every image, and the app recomputes the CRC of the saved file.
 *
 * Files MBOOT wrote are never deleted by the app (see retire): MBOOT ends a FAT chain with 0xFFFFFFFF, and when
 * Android reuses those clusters it keeps the top 4 bits, which MBOOT's own FAT reader then refuses ("Invalid FAT
 * entry"). An old copy is renamed instead, which frees nothing.
 */
object Wizard {

    enum class Stage {
        NO_DRIVE,      // no USB drive found
        FRESH,         // nothing done yet
        PROBE_READY,   // probe.txt written, waiting to be run in MBOOT
        PROBE_DONE,    // PTABLE.BIN + ENV.TXT saved
        BACKUP_READY,  // backup.txt written, waiting to be run in MBOOT
        BACKUP_BAD,    // the backup marker exists but the images do not verify
        BACKUP_DONE,   // images saved and verified
        PATCHED,       // patched image + flash.txt/check.txt/rollback.txt written
        FLASH_DONE     // FLASH.OK written by MBOOT
    }

    /** [script] is the file to start in MBOOT next (for the *_READY and PATCHED stages). */
    class Info(
        val stage: Stage,
        val root: File?,
        val script: String? = null,
        val backupImage: File? = null,
        val detail: String = ""
    )

    private val OWN_FILES = listOf("probe.txt", "backup.txt", "flash.txt", "PTABLE.BIN", "PROBE.OK", "FLASH.OK")

    /** The drive the flow works on: the one that already holds our files, else the first writable one. */
    fun pickRoot(context: Context): File? {
        val roots = MbootScripts.findUsbRoots(context)
        return roots.firstOrNull { r -> OWN_FILES.any { File(r, it).isFile } } ?: roots.firstOrNull()
    }

    /** Renames files MBOOT wrote out of the way (name.old1, name.old2, ...). Never deletes them. */
    fun retire(root: File, names: List<String>) {
        for (n in names) {
            val f = File(root, n)
            if (!f.exists()) continue
            var k = 1
            while (File(root, "$n.old$k").exists()) k++
            f.renameTo(File(root, "$n.old$k"))
        }
    }

    /** A line like "# Files: a b c" or "# Marker: x" of backup.txt. */
    private fun backupHeader(root: File, key: String): String? {
        val f = File(root, "backup.txt")
        if (!f.isFile) return null
        return f.readLines().firstOrNull { it.startsWith("# $key:") }?.removePrefix("# $key:")?.trim()
    }

    class Verified(val ok: Boolean, val images: Map<String, File>, val detail: String)

    /**
     * Checks the saved images against backup.txt and the probe: the size must be the partition's size and the
     * CRC32 of the file must equal the one MBOOT stored next to it (little-endian u32 in the .CRC file).
     * [images] maps the partition name to its file.
     */
    private fun verifyBackup(context: Context, root: File): Verified {
        val script = File(root, "backup.txt")
        if (!script.isFile) return Verified(false, emptyMap(), "backup.txt")
        val layout = try { MbootScripts.loadLayout(context, root) } catch (e: Exception) {
            return Verified(false, emptyMap(), e.message ?: "probe")
        }
        val partRe = Regex("""^echo BACKUP_(.+)_0x[0-9A-Fa-f]+_bytes""")
        val fileRe = Regex("""^fatwrite usb 0:1 \S+ (\S+\.IMG) """)
        var part: String? = null
        val map = LinkedHashMap<String, File>()
        for (line in script.readLines()) {
            partRe.find(line)?.let { part = it.groupValues[1] }
            fileRe.find(line)?.let { m -> part?.let { map[it] = File(root, m.groupValues[1]) } }
        }
        if (map.isEmpty()) return Verified(false, emptyMap(), "backup.txt")
        for ((p, f) in map) {
            val want = layout.partitions[p]?.bytes
            if (!f.isFile || want == null || f.length() != want) return Verified(false, map, "${f.name}: size")
            val crcFile = File(root, f.name.removeSuffix(".IMG") + ".CRC")
            val b = if (crcFile.isFile) crcFile.readBytes() else null
            if (b == null || b.size != 4) return Verified(false, map, "${f.name}: CRC file")
            val saved = ((b[0].toLong() and 0xFF) or ((b[1].toLong() and 0xFF) shl 8) or
                ((b[2].toLong() and 0xFF) shl 16) or ((b[3].toLong() and 0xFF) shl 24))
            if ("%08X".format(saved) != MbootScripts.crc32Hex(f)) return Verified(false, map, "${f.name}: CRC")
        }
        return Verified(true, map, "")
    }

    /** Recognises the current step. [slot] is the active boot slot ("a"/"b") or null. Reads files: call off the UI thread. */
    fun detect(context: Context, slot: String?): Info {
        val root = pickRoot(context) ?: return Info(Stage.NO_DRIVE, null)

        fun has(n: String) = File(root, n).isFile
        val probeOk = has("PROBE.OK") && has("PTABLE.BIN") && has("ENV.TXT")

        if (has("FLASH.OK") && (!has("ROLLBACK.OK") || File(root, "FLASH.OK").lastModified() >= File(root, "ROLLBACK.OK").lastModified())) {
            return Info(Stage.FLASH_DONE, root)
        }
        if (!probeOk) {
            return if (has("probe.txt")) Info(Stage.PROBE_READY, root, script = "probe.txt") else Info(Stage.FRESH, root)
        }
        if (!has("backup.txt")) return Info(Stage.PROBE_DONE, root)
        val marker = backupHeader(root, "Marker") ?: "BACKUP.OK"
        if (!has(marker)) return Info(Stage.BACKUP_READY, root, script = "backup.txt")

        val v = verifyBackup(context, root)
        if (!v.ok) return Info(Stage.BACKUP_BAD, root, detail = v.detail)
        val image = slot?.let { v.images["boot_$it"] }
        if (has("flash.txt") && has("check.txt") && has("rollback.txt")) {
            return Info(Stage.PATCHED, root, script = "check.txt", backupImage = image)
        }
        return Info(Stage.BACKUP_DONE, root, backupImage = image)
    }
}
