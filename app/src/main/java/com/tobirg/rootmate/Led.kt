package com.tobirg.rootmate

import android.content.Context
import java.io.File

/**
 * Power LED control through the ledctl kernel module (sources in /ledctl, see docs/led.md).
 * The module and its Magisk service live in /data/adb/modules/ledctl; settings are module parameters
 * (/sys/module/ledctl/parameters, applied at once) mirrored into ledctl.conf (applied at every boot).
 * All functions run root shell scripts: call them off the UI thread.
 */
object Led {

    const val MOD = "/data/adb/modules/ledctl"
    private const val PAR = "/sys/module/ledctl/parameters"
    /** module.prop "version" of the module shipped in assets/ledctl */
    const val BUNDLED_VERSION = "1.0"

    enum class Probe { SUPPORTED, UNSUPPORTED, NO_ROOT }

    /**
     * Dry run of the bundled module (insmod probe_only=1): the kernel checks the symbol CRCs (the kernel release in
     * vermagic is not compared when modversions are on, so it says nothing), then the module disassembles the
     * driver and validates the device and values, writes nothing and fails with ECANCELED ("Operation canceled").
     * Any other result means it would refuse to run here. A loaded ledctl counts as supported (it passed the same).
     */
    fun probe(context: Context): Probe {
        val ko = File(context.cacheDir, "ledctl_probe.ko")
        context.assets.open("ledctl/ledctl.ko").use { i -> ko.outputStream().use { i.copyTo(it) } }
        val r = Root.run(
            """
            echo ROOT=1
            if [ -d $PAR ]; then echo PROBE_LOADED; else insmod ${ko.absolutePath} probe_only=1 2>&1; fi
            """.trimIndent()
        )
        ko.delete()
        return when {
            !r.out.contains("ROOT=1") -> Probe.NO_ROOT
            r.out.contains("PROBE_LOADED") || r.out.contains("operation cancel", ignoreCase = true) -> Probe.SUPPORTED
            else -> Probe.UNSUPPORTED
        }
    }

    /** The probe answer holds for one kernel build and one build of the bundled module (by checksum, not version). */
    fun probeCacheKey(context: Context): String {
        val u = runCatching { android.system.Os.uname() }.getOrNull()
        return "probe|${u?.release}|${u?.version}|${bundledSums(context)["ledctl.ko"]}"
    }

    val KEYS = listOf("sleep_led", "sleep_level", "on_led", "on_level")
    val DEFAULTS = mapOf("sleep_led" to 0, "sleep_level" to 100, "on_led" to -1, "on_level" to 100)

    data class State(
        val root: Boolean,
        val installed: Boolean,
        val installedVersion: String?,
        val loaded: Boolean,
        /** why the kernel or the module refused to load, if it did */
        val error: String?,
        val values: Map<String, Int>,
        /** an installed file differs from the one in assets/ledctl (the version stays 1.0 until a release) */
        val filesDiffer: Boolean = false,
    ) {
        val needsUpdate get() = installed && (installedVersion != BUNDLED_VERSION || filesDiffer)
    }

    /** MD5 of every file shipped in assets/ledctl, by name. */
    private fun bundledSums(context: Context): Map<String, String> =
        context.assets.list("ledctl").orEmpty().associateWith { name ->
            val md = java.security.MessageDigest.getInstance("MD5")
            context.assets.open("ledctl/$name").use { i ->
                val buf = ByteArray(8192)
                while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }

    fun read(context: Context): State {
        val sums = bundledSums(context)
        val r = Root.run(
            """
            M=$MOD; P=$PAR
            echo ROOT=1
            for f in ${sums.keys.joinToString(" ")}; do echo "SUM_${'$'}f=${'$'}(md5sum ${'$'}M/${'$'}f 2>/dev/null | cut -d' ' -f1)"; done
            [ -f ${'$'}M/ledctl.ko ] && echo INSTALLED=1
            echo VERSION=${'$'}(sed -n 's/^version=//p' ${'$'}M/module.prop 2>/dev/null)
            [ -d ${'$'}P ] && echo LOADED=1
            for k in ${KEYS.joinToString(" ")}; do
              v=${'$'}(cat ${'$'}P/${'$'}k 2>/dev/null)
              [ -z "${'$'}v" ] && v=${'$'}(sed -n "s/^${'$'}k=//p" ${'$'}M/ledctl.conf 2>/dev/null)
              echo "V_${'$'}k=${'$'}v"
            done
            echo "INSMOD=${'$'}(tail -1 ${'$'}M/insmod.log 2>/dev/null)"
            echo "REFUSED=${'$'}(dmesg 2>/dev/null | grep 'ledctl: refused' | tail -1 | sed 's/.*refused: //')"
            """.trimIndent()
        )
        val kv = r.out.lines().mapNotNull { l -> l.indexOf('=').takeIf { it > 0 }?.let { l.substring(0, it) to l.substring(it + 1).trim() } }.toMap()
        if (kv["ROOT"] != "1") return State(false, false, null, false, r.out.trim().take(200), DEFAULTS)
        val values = KEYS.associateWith { k -> kv["V_$k"]?.toIntOrNull() ?: DEFAULTS.getValue(k) }
        val loaded = kv["LOADED"] == "1"
        val error = if (loaded) null else (kv["REFUSED"].orEmpty().ifEmpty { kv["INSMOD"].orEmpty() }).ifEmpty { null }
        val differs = sums.any { (name, sum) -> kv["SUM_$name"] != sum }
        return State(true, kv["INSTALLED"] == "1", kv["VERSION"]?.ifEmpty { null }, loaded, error, values, differs)
    }

    /**
     * Copies the module from assets into /data/adb/modules/ledctl (Magisk then starts it at every boot) and loads it
     * now. On a kernel that refuses it the folder is removed again, so nothing is left to fail at the next boot.
     * Returns null on success or the error text.
     */
    fun install(context: Context): String? {
        val src = File(context.cacheDir, "ledctl").apply { deleteRecursively(); mkdirs() }
        for (name in context.assets.list("ledctl").orEmpty()) {
            context.assets.open("ledctl/$name").use { i -> File(src, name).outputStream().use { i.copyTo(it) } }
        }
        val conf = DEFAULTS.entries.joinToString("") { "${it.key}=${it.value}\\n" }
        val r = Root.run(
            """
            M=$MOD; P=$PAR; S=${src.absolutePath}
            ${stopWatcher()}
            [ -d ${'$'}P ] && rmmod ledctl
            mkdir -p ${'$'}M
            rm -f ${'$'}M/sleep_led ${'$'}M/disable ${'$'}M/remove ${'$'}M/insmod.log
            cp ${'$'}S/* ${'$'}M/
            chmod 644 ${'$'}M/*; chmod 755 ${'$'}M/service.sh ${'$'}M/watch.sh
            [ -f ${'$'}M/ledctl.conf ] || printf '$conf' > ${'$'}M/ledctl.conf
            rm -rf ${'$'}S
            sh ${'$'}M/service.sh
            if [ -d ${'$'}P ]; then echo LED_OK; else
              echo "LED_FAIL ${'$'}(cat ${'$'}M/insmod.log) ${'$'}(dmesg | grep 'ledctl: refused' | tail -1 | sed 's/.*refused: //')"
              ${stopWatcher()}
              rm -rf ${'$'}M
            fi
            """.trimIndent(), 60
        )
        return if (r.out.contains("LED_OK")) null
        else r.out.substringAfter("LED_FAIL", r.out).trim().ifEmpty { "error ${r.code}" }
    }

    /** Unloads the module (the driver gets its own values back) and deletes the Magisk module folder. */
    fun remove(): Boolean {
        val r = Root.run(
            """
            M=$MOD
            ${stopWatcher()}
            [ -d $PAR ] && rmmod ledctl
            rm -rf ${'$'}M
            [ -d $PAR ] || [ -d ${'$'}M ] || echo LED_REMOVED
            """.trimIndent()
        )
        return r.out.contains("LED_REMOVED")
    }

    /** Applies one setting now (if the module is loaded) and stores it for the next boots. */
    fun set(key: String, value: Int): Boolean {
        require(key in KEYS)
        val r = Root.run(
            """
            C=$MOD/ledctl.conf
            if [ -f ${'$'}C ]; then
              if grep -q '^$key=' ${'$'}C; then sed -i 's/^$key=.*/$key=$value/' ${'$'}C; else echo '$key=$value' >> ${'$'}C; fi
            fi
            if [ -d $PAR ]; then echo $value > $PAR/$key && echo LED_SET; else echo LED_SET; fi
            """.trimIndent()
        )
        return r.out.contains("LED_SET")
    }

    /** watch.sh runs in its own session (setsid): killing its process group stops logcat and the loop too. */
    private fun stopWatcher() =
        "[ -f $MOD/watch.pid ] && { kill -TERM -\$(cat $MOD/watch.pid) 2>/dev/null; kill \$(cat $MOD/watch.pid) 2>/dev/null; rm -f $MOD/watch.pid; }"
}
