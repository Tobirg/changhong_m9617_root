package com.tobirg.rootmate

import java.util.concurrent.TimeUnit

/** Runs shell scripts as root through Magisk's `su`. Call off the UI thread: the first call shows Magisk's prompt. */
object Root {

    data class Result(val code: Int, val out: String) {
        val ok get() = code == 0
    }

    /** [script] goes to su's stdin; stderr is merged into [Result.out]. */
    fun run(script: String, timeoutSec: Long = 30): Result = try {
        val p = ProcessBuilder("su").redirectErrorStream(true).start()
        val out = StringBuilder()
        // read on a side thread so a chatty script cannot block on a full pipe while we wait for it
        val reader = Thread { out.append(p.inputStream.bufferedReader().readText()) }.apply { start() }
        p.outputStream.bufferedWriter().use { it.write(script); it.write("\nexit\n") }
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroy()
            Result(-2, "timeout")
        } else {
            reader.join(2000)
            Result(p.exitValue(), out.toString())
        }
    } catch (e: Exception) {
        Result(-1, e.message ?: e.toString())
    }
}
