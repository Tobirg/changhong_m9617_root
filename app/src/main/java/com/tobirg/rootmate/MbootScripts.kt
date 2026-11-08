package com.tobirg.rootmate

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.CRC32

/**
 * Builds the MBOOT scripts that `udstar` runs from the USB drive, from the templates in assets/mboot:
 *
 * All scripts live in the root of the drive and are started as `udstar <name>`.
 *  - probe.txt    : saves the eMMC partition table (PTABLE.BIN) and the MBOOT environment (ENV.TXT) to the
 *                   drive. It uses nothing that belongs to a particular TV: the scratch RAM address comes
 *                   from the environment of the TV itself (`${MI_KERNEL_POOL2_ADR}` is expanded by MBOOT).
 *  - backup.txt   : saves the boot partitions and misc; names, sizes and RAM buffers come from the probe.
 *  - flash.txt, check.txt, rollback.txt : flash a patched boot image, dry run, and restore the original.
 *
 * Nothing about the TV is built in: partition names and sizes are read from PTABLE.BIN, the RAM buffers are
 * laid out inside the free pool that ENV.TXT names. If the probe files are missing or do not make sense the
 * scripts are not generated at all.
 *
 * udstar only runs plain linear scripts: no if/for, and any command that fails stops the script. Because of
 * that the templates do their "conditions" with `cmp`, and the safety of every script rests on the guards in
 * the template, not on this class. Template rules: "@W " lines write to the eMMC and exist only in the real
 * script, "@D " only in the dry run, "@B " only for partitions that must start with the Android boot magic;
 * the marker is stripped. uart_dump/tools/mboot_gen.py renders the same templates for tests on the TV, keep the
 * rules in both places the same.
 */
object MbootScripts {

    class ScriptException(message: String) : Exception(message)

    class Result(val dir: File, val files: List<File>, val wipe: Boolean)

    /** One entry of the eMMC partition table: [start] and [blocks] count 512-byte blocks. */
    class PEntry(val name: String, val start: Long, val blocks: Long) {
        val bytes: Long get() = blocks * 512
    }

    /** What the probe found out: the partitions and the free RAM pool MBOOT reserves for the kernel. */
    class Layout(val partitions: Map<String, PEntry>, val pool: Long, val poolLen: Long)

    private const val MIB = 0x100000L

    // ---------------------------------------------------------------------
    // Small helpers
    // ---------------------------------------------------------------------

    /** CRC32 of a whole file as 8 upper-case hex digits (the form `crc32` in MBOOT prints). */
    fun crc32Hex(file: File): String {
        val crc = CRC32()
        val buf = ByteArray(1 shl 20)
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                crc.update(buf, 0, n)
            }
        }
        return "%08X".format(crc.value)
    }

    /** Root of a removable volume ("/storage/XXXX-XXXX"): the folder MBOOT sees as the drive root. Null for internal storage. */
    fun volumeRoot(file: File): File? {
        val parts = file.absolutePath.split("/")
        if (parts.size > 3 && parts[1] == "storage" && parts[2] != "emulated" && parts[2] != "self") {
            return File("/storage/${parts[2]}")
        }
        return null
    }

    /**
     * Removable drives to write to. A normal app may not list /storage on current Android, so the mounted
     * removable volumes come from StorageManager (getDirectory on API 30+, the hidden getPath before that);
     * the plain /storage listing is only a fallback for firmware that still allows it.
     */
    fun findUsbRoots(context: Context): List<File> {
        val dirs = ArrayList<File>()
        try {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as android.os.storage.StorageManager
            for (v in sm.storageVolumes) {
                if (!v.isRemovable || v.state != android.os.Environment.MEDIA_MOUNTED) continue
                val dir: File? = if (android.os.Build.VERSION.SDK_INT >= 30) v.directory else try {
                    File(v.javaClass.getMethod("getPath").invoke(v) as String)
                } catch (e: Exception) {
                    null
                }
                if (dir != null) dirs.add(dir)
            }
        } catch (e: Exception) {
            // fall through to the listing below
        }
        if (dirs.isEmpty()) {
            (File("/storage").listFiles() ?: emptyArray())
                .filter { it.isDirectory && it.name != "emulated" && it.name != "self" }
                .forEach { dirs.add(it) }
        }
        return dirs.filter { it.canWrite() }
    }

    /** Path relative to the volume root with '/' separators, as `fatload` expects it. */
    private fun relative(root: File, file: File): String? {
        val prefix = root.absolutePath + "/"
        val path = file.absolutePath
        return if (path.startsWith(prefix)) path.substring(prefix.length) else null
    }

    /** MBOOT splits commands on spaces and ';', so keep the file names to plain safe characters. */
    private fun isSafePath(p: String) = p.isNotEmpty() && p.all { it.isLetterOrDigit() && it.code < 128 || it in "._-/" }

    private fun tpl(context: Context, name: String) =
        context.assets.open("mboot/$name").bufferedReader(Charsets.US_ASCII).use { it.readText() }

    private fun crlf(text: String) = text.lines().joinToString("\r\n").trimEnd('\r', '\n') + "\r\n"

    private fun fill(text: String, values: Map<String, String>): String {
        var t = text
        for ((k, v) in values) t = t.replace("{{$k}}", v)
        return t
    }

    private fun hex(v: Long) = "0x%X".format(v)

    private fun now() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())

    /** Writes [text] to [dir]/[name] through a .part file so a half-written script never has the final name. */
    private fun writeAtomically(context: Context, dir: File, name: String, text: String): File {
        dir.mkdirs()
        if (!dir.canWrite()) throw ScriptException(context.getString(R.string.err_not_writable, dir.absolutePath))
        val dest = File(dir, name)
        val part = File(dir, "$name.part")
        java.io.FileOutputStream(part).use { out ->
            out.write(text.toByteArray(Charsets.US_ASCII))
            out.fd.sync()
        }
        dest.delete()
        if (!part.renameTo(dest)) throw ScriptException(context.getString(R.string.err_save, dest.absolutePath))
        return dest
    }

    // ---------------------------------------------------------------------
    // Probe: PTABLE.BIN + ENV.TXT
    // ---------------------------------------------------------------------

    /** Writes probe.txt into the drive root [dir] (no values from the TV in it). Older probe results are renamed, not deleted. */
    fun writeProbe(context: Context, dir: File): File {
        Wizard.retire(dir, listOf("PTABLE.BIN", "ENV.TXT", "PROBE.OK"))
        return writeAtomically(context, dir, "probe.txt", crlf(tpl(context, "probe.tpl")))
    }

    /**
     * The eMMC partition table: 512-byte entries from offset 0x200, each `u32 0x5840, u32 ?, u32 start,
     * u32 length (blocks), char name[]` from offset 0x10. Same numbers as `mmc part` in MBOOT.
     */
    fun parsePtable(data: ByteArray): Map<String, PEntry> {
        fun u32(o: Int) = (data[o].toLong() and 0xFF) or ((data[o + 1].toLong() and 0xFF) shl 8) or
            ((data[o + 2].toLong() and 0xFF) shl 16) or ((data[o + 3].toLong() and 0xFF) shl 24)
        val out = LinkedHashMap<String, PEntry>()
        var off = 0x200
        while (off + 0x200 <= data.size && u32(off) == 0x5840L) {
            val sb = StringBuilder()
            var i = off + 16
            while (i < off + 16 + 48 && data[i].toInt() != 0) {
                sb.append((data[i].toInt() and 0x7F).toChar())
                i++
            }
            val name = sb.toString()
            if (name.isNotEmpty()) out[name] = PEntry(name, u32(off + 8), u32(off + 12))
            off += 0x200
        }
        return out
    }

    /** `env export -t` text: NAME=value lines, possibly NUL-separated, ended by an empty entry. */
    fun parseEnv(data: ByteArray): Map<String, String> {
        val text = String(data, Charsets.ISO_8859_1)
        val end = text.indexOf("\u0000\u0000").let { if (it < 0) text.length else it }
        val env = HashMap<String, String>()
        for (line in text.substring(0, end).split('\u0000', '\n')) {
            val eq = line.indexOf('=')
            if (eq > 0) env[line.substring(0, eq)] = line.substring(eq + 1)
        }
        return env
    }

    /** Reads the probe files from the drive root [dir]; throws a readable error if they are missing or make no sense. */
    fun loadLayout(context: Context, dir: File): Layout {
        val ptable = File(dir, "PTABLE.BIN")
        val envFile = File(dir, "ENV.TXT")
        if (!ptable.isFile || !envFile.isFile) throw ScriptException(context.getString(R.string.err_probe_missing))
        val parts = parsePtable(ptable.readBytes())
        val env = parseEnv(envFile.readBytes())
        val pool = env["MI_KERNEL_POOL2_ADR"]?.let { runCatching { java.lang.Long.decode(it) }.getOrNull() }
        val poolLen = env["MI_KERNEL_POOL2_LEN"]?.let { runCatching { java.lang.Long.decode(it) }.getOrNull() }
        if (parts.size < 2 || pool == null || poolLen == null) throw ScriptException(context.getString(R.string.err_probe_bad))
        return Layout(parts, pool, poolLen)
    }

    /**
     * Three scratch buffers in the free pool, each as big as the biggest image (rounded up to 1 MiB): A holds
     * the data, B the readback or the backup, C the small things (misc, expected values at C + 0x10000).
     * The placeholders carry the full addresses so the scripts need no arithmetic.
     */
    private fun bufferValues(context: Context, layout: Layout, maxSize: Long): Map<String, String> {
        val slot = (maxSize + MIB - 1) / MIB * MIB
        if (2 * slot + MIB > layout.poolLen) throw ScriptException(context.getString(R.string.err_probe_pool))
        val a = layout.pool
        val b = a + slot
        val c = b + slot
        val x = c + 0x10000
        return mapOf(
            "A" to hex(a), "B" to hex(b), "C" to hex(c), "C4" to hex(c + 4), "C20" to hex(c + 0x20), "C800" to hex(c + 0x800),
            "X0" to hex(x), "X4" to hex(x + 4), "X10" to hex(x + 0x10), "X20" to hex(x + 0x20),
            "X30" to hex(x + 0x30), "X40" to hex(x + 0x40)
        )
    }

    // ---------------------------------------------------------------------
    // Backup script (backup.txt)
    // ---------------------------------------------------------------------

    /** Partitions that get saved: boot, init_boot, vendor_boot (with or without _a/_b) and misc. */
    private val WANTED = Regex("^((init_|vendor_)?boot(_[ab])?|misc)$")

    /** Short 8.3-safe names (MBOOT's fatwrite does not make long ones); the rest is upper-cased as it is. */
    private val ALIAS = mapOf(
        "init_boot_a" to "IBOOT_A", "init_boot_b" to "IBOOT_B", "init_boot" to "IBOOT",
        "vendor_boot_a" to "VBOOT_A", "vendor_boot_b" to "VBOOT_B", "vendor_boot" to "VBOOT"
    )

    class BackupResult(val file: File, val names: List<String>)

    /**
     * Same index for every file, and for the marker: an existing image is never overwritten (MBOOT recreates an
     * existing file, which damages it), the names just get a free index. Returns the index and the names.
     */
    private fun backupNames(dir: File, parts: List<PEntry>): Pair<Int, Map<String, String>> {
        val existing = (dir.list() ?: emptyArray()).map { it.lowercase() }.toSet()
        for (n in 1..9) {
            val names = parts.associate { p ->
                val base = ALIAS[p.name] ?: p.name.uppercase()
                p.name to (if (n == 1) "$base.IMG" else "${base}_$n.IMG")
            }
            val crcs = names.values.map { it.removeSuffix(".IMG") + ".CRC" }
            if ((names.values + crcs + backupMarker(n)).none { it.lowercase() in existing }) return n to names
        }
        return 0 to emptyMap()
    }

    private fun backupMarker(n: Int) = if (n == 1) "BACKUP.OK" else "BACKUP_$n.OK"

    /** Writes backup.txt into the drive root [dir] for every wanted partition the probe found. */
    fun writeBackup(context: Context, dir: File): BackupResult {
        val layout = loadLayout(context, dir)
        val parts = layout.partitions.values.filter { WANTED.matches(it.name) }
        if (parts.none { it.name != "misc" }) throw ScriptException(context.getString(R.string.err_probe_no_boot))
        for (p in parts) {
            if (p.bytes <= 0 || p.bytes % 4 != 0L) {
                throw ScriptException(context.getString(R.string.err_backup_part_size, p.name, p.bytes))
            }
        }
        val (index, names) = backupNames(dir, parts)
        if (names.isEmpty()) throw ScriptException(context.getString(R.string.err_backup_names_taken))
        val buffers = bufferValues(context, layout, parts.maxOf { it.bytes })

        val partTpl = tpl(context, "backup_part.tpl")
        val blocks = parts.joinToString("\n") { p ->
            val magic = p.name.startsWith("boot") || p.name.startsWith("init_boot")
            val lines = partTpl.lines().mapNotNull { line ->
                if (line.startsWith("@B ")) (if (magic) line.substring(3) else null) else line
            }
            val values = buffers + mapOf(
                "PART" to p.name,
                "SIZE" to hex(p.bytes),
                "WORDS" to hex(p.bytes / 4),
                "FILE" to names.getValue(p.name),
                "CRCFILE" to names.getValue(p.name).removeSuffix(".IMG") + ".CRC"
            )
            fill(lines.joinToString("\n"), values).trimEnd('\r', '\n')
        }
        val text = fill(
            tpl(context, "backup.tpl"),
            buffers + mapOf(
                "WHEN" to now(),
                "SIZE_SOURCE" to "probe",
                "FILES" to parts.joinToString(" ") { names.getValue(it.name) },
                "MARKER" to backupMarker(index),
                "PARTS" to blocks
            )
        )
        val dest = writeAtomically(context, dir, "backup.txt", crlf(text))
        return BackupResult(dest, parts.map { names.getValue(it.name) })
    }

    // ---------------------------------------------------------------------
    // Flash, dry run and rollback (flash.txt, check.txt, rollback.txt)
    // ---------------------------------------------------------------------

    /** Applies the "@W " / "@D " template rules to one template and fills the placeholders. */
    fun render(template: String, values: Map<String, String>, real: Boolean): String {
        val out = StringBuilder()
        for (raw in template.lines()) {
            var line = raw
            if (line.startsWith("@W ")) {
                if (!real) continue
                line = line.substring(3)
            } else if (line.startsWith("@D ")) {
                if (real) continue
                line = line.substring(3)
            }
            out.append(fill(line, values)).append("\r\n")
        }
        return out.toString().trimEnd('\r', '\n') + "\r\n"
    }

    /**
     * Writes flash.txt, check.txt and rollback.txt to the root of the drive that holds the patched image.
     * [original] is the untouched boot image taken from the eMMC (the rollback source), [patched] the
     * Magisk-patched result, [slot] "a" or "b", [wipe] whether the flash script should wipe data (needed
     * when the verified-boot state changes). The image size is the size of boot_<slot> in the partition table.
     */
    fun generate(context: Context, original: File, patched: File, slot: String, wipe: Boolean): Result {
        if (slot != "a" && slot != "b") throw ScriptException("slot")
        val root = volumeRoot(patched) ?: throw ScriptException(context.getString(R.string.err_script_not_usb, patched.absolutePath))
        val origRel = relative(root, original) ?: throw ScriptException(context.getString(R.string.err_script_other_drive))
        val newRel = relative(root, patched) ?: throw ScriptException(context.getString(R.string.err_script_other_drive))
        for (p in listOf(origRel, newRel)) {
            if (!isSafePath(p)) throw ScriptException(context.getString(R.string.err_script_bad_name, p))
        }
        val dir = root
        val layout = loadLayout(context, dir)
        Wizard.retire(dir, listOf("FLASH.OK", "ROLLBACK.OK"))
        val entry = layout.partitions["boot_$slot"] ?: throw ScriptException(context.getString(R.string.err_probe_no_boot))
        val size = entry.bytes
        if (original.length() != size || patched.length() != size) {
            throw ScriptException(context.getString(R.string.err_script_size, size, original.length(), patched.length()))
        }
        val values = bufferValues(context, layout, size) + mapOf(
            "SLOT" to slot,
            "SLOTHEX" to if (slot == "a") "61" else "62",
            "ORIG" to origRel,
            "NEW" to newRel,
            "CRC_ORIG" to crc32Hex(original),
            "CRC_NEW" to crc32Hex(patched),
            "SIZE" to hex(size),
            "WORDS" to hex(size / 4),
            "WHEN" to now(),
            "WIPE_CMD" to if (wipe) "recovery_wipe_partition data" else "echo wipe_skipped_state_already_orange"
        )
        val flash = tpl(context, "flash.tpl")
        val jobs = listOf(
            "flash.txt" to render(flash, values, true),
            "check.txt" to render(flash, values, false),
            "rollback.txt" to render(tpl(context, "rollback.tpl"), values, true)
        )
        return Result(dir, jobs.map { (name, text) -> writeAtomically(context, dir, name, text) }, wipe)
    }
}
