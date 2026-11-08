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
        NO_PERMISSION, // no "all files access": the drives cannot be read at all
        NO_DRIVE,      // no USB drive found
        DRIVE_MISSING, // the drive chosen earlier is not plugged in
        CHOOSE_DRIVE,  // several drives and nothing tells which one: the user picks once
        BAD_DRIVE,     // the chosen drive is one MBOOT cannot read (not FAT, or not the first partition)
        FRESH,         // nothing done yet
        PROBE_READY,   // probe.txt written, waiting to be run in MBOOT
        PROBE_DONE,    // PTABLE.BIN + ENV.TXT saved
        BACKUP_READY,  // backup.txt written, waiting to be run in MBOOT
        BACKUP_BAD,    // the backup marker exists but the images do not verify
        BACKUP_DONE,   // images saved and verified
        PATCHED,       // patched image + flash.txt/check.txt/rollback.txt written
        FLASH_DONE     // FLASH.OK written by MBOOT
    }

    /**
     * A mounted removable drive. [id] is its folder name under /storage (the FAT volume serial, e.g. 7A06-0FA1;
     * it changes when the drive is formatted). [fs] and [partition] come from /proc/self/mounts and are null when
     * the firmware does not show them; [partition] 0 is a drive without a partition table.
     */
    class Drive(val root: File, val id: String, val label: String, val fs: String?, val partition: Int?, val total: Long) {
        val isFat get() = fs == null || fs == "vfat" || fs == "msdos"
        /** MBOOT reads `usb 0:1`: the first partition (or a whole-disk FAT, which U-Boot treats as partition 1). */
        val isFirstPartition get() = partition == null || partition <= 1
    }

    /**
     * [drive] is the drive the flow works on, [drives] every mounted one. [script] is the file to start in MBOOT
     * next (for the *_READY and PATCHED stages).
     */
    class Info(
        val stage: Stage,
        val root: File?,
        val script: String? = null,
        val backupImage: File? = null,
        val detail: String = "",
        val drive: Drive? = null,
        val drives: List<Drive> = emptyList()
    )

    private val OWN_FILES = listOf("probe.txt", "backup.txt", "flash.txt", "PTABLE.BIN", "PROBE.OK", "FLASH.OK")

    private fun hasOwnFiles(root: File) = OWN_FILES.any { File(root, it).isFile }

    /** Filesystem type and partition number of every /mnt/media_rw/<id> mount ("public:8,1" = sda1). */
    private fun mountInfo(): Map<String, Pair<String, Int?>> {
        val out = HashMap<String, Pair<String, Int?>>()
        try {
            File("/proc/self/mounts").forEachLine { line ->
                val f = line.split(' ')
                if (f.size < 3 || !f[1].startsWith("/mnt/media_rw/")) return@forEachLine
                val m = Regex("""public:(\d+),(\d+)$""").find(f[0])
                // SCSI disks (USB drives) have major 8 and 16 minors per disk: minor % 16 is the partition
                val part = m?.let { if (it.groupValues[1] == "8") it.groupValues[2].toInt() % 16 else null }
                out[f[1].removePrefix("/mnt/media_rw/")] = f[2] to part
            }
        } catch (e: Exception) {
            // unknown: the checks are skipped
        }
        return out
    }

    /** The mounted drive that holds [file], or null (internal storage, or not found). */
    fun driveOf(context: Context, file: File): Drive? {
        val root = MbootScripts.volumeRoot(file) ?: return null
        return drives(context).firstOrNull { it.root.absolutePath == root.absolutePath }
    }

    /** Every mounted removable drive the app can write to, with its label, filesystem and partition. */
    fun drives(context: Context): List<Drive> {
        val labels = HashMap<String, String>()
        try {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as android.os.storage.StorageManager
            for (v in sm.storageVolumes) {
                if (!v.isRemovable) continue
                val dir = if (android.os.Build.VERSION.SDK_INT >= 30) v.directory else null
                val key = dir?.name ?: v.uuid ?: continue
                labels[key] = v.getDescription(context)
            }
        } catch (e: Exception) {
        }
        val mounts = mountInfo()
        return MbootScripts.findUsbRoots(context).map { root ->
            val m = mounts[root.name]
            Drive(root, root.name, labels[root.name] ?: root.name, m?.first, m?.second, root.totalSpace)
        }
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
            if ("%08X".format(saved) != cachedCrc(f)) return Verified(false, map, "${f.name}: CRC")
        }
        return Verified(true, map, "")
    }

    /** CRC32 of the backup images is recomputed only when a file changed (the stage is checked on every resume). */
    private val crcCache = HashMap<String, Pair<String, String>>()

    @Synchronized
    private fun cachedCrc(f: File): String {
        val stamp = "${f.length()}:${f.lastModified()}"
        crcCache[f.absolutePath]?.let { (s, crc) -> if (s == stamp) return crc }
        return MbootScripts.crc32Hex(f).also { crcCache[f.absolutePath] = stamp to it }
    }

    /**
     * Recognises the current step. [slot] is the active boot slot ("a"/"b") or null, [chosenId] the drive picked
     * earlier (it is kept, so the flow never jumps to another drive by itself). Without a choice the drive is
     * picked only when that is unambiguous: the only drive, or the only one that holds our files; the caller
     * remembers it. Reads files: call off the UI thread.
     */
    fun detect(context: Context, slot: String?, chosenId: String?): Info {
        if (!Perms.hasStorage(context)) return Info(Stage.NO_PERMISSION, null)
        val all = drives(context)
        val drive = if (chosenId != null) {
            all.firstOrNull { it.id == chosenId } ?: return Info(Stage.DRIVE_MISSING, null, detail = chosenId, drives = all)
        } else {
            val withFiles = all.filter { hasOwnFiles(it.root) }
            when {
                all.isEmpty() -> return Info(Stage.NO_DRIVE, null)
                withFiles.size == 1 -> withFiles[0]
                all.size == 1 -> all[0]
                else -> return Info(Stage.CHOOSE_DRIVE, null, drives = all)
            }
        }
        if (!drive.isFat || !drive.isFirstPartition) return Info(Stage.BAD_DRIVE, null, drive = drive, drives = all)
        return detectOn(context, drive.root, slot).let {
            Info(it.stage, it.root, it.script, it.backupImage, it.detail, drive, all)
        }
    }

    private fun detectOn(context: Context, root: File, slot: String?): Info {
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
