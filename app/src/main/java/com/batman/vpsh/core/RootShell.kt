package com.batman.vpsh.core

import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class RootShell {

    data class Result(val exitCode: Int, val output: List<String>)

    private var process: Process? = null
    private var stdin: DataOutputStream? = null
    private var stdout: BufferedReader? = null
    private val lock = ReentrantLock()

    @Volatile var isRooted: Boolean = false
        private set

    fun open(): Boolean = lock.withLock {
        if (process != null) return isRooted
        return try {
            val p = ProcessBuilder("su").redirectErrorStream(true).start()
            process = p
            stdin = DataOutputStream(p.outputStream)
            stdout = BufferedReader(InputStreamReader(p.inputStream))
            
            val res = runLocked("id")
            isRooted = res.exitCode == 0 && res.output.any { it.contains("uid=0") }
            isRooted
        } catch (_: Exception) {
            isRooted = false
            false
        }
    }

    fun run(command: String): Result = lock.withLock { runLocked(command) }

    private fun runLocked(command: String): Result {
        val si = stdin ?: return Result(-1, emptyList())
        val so = stdout ?: return Result(-1, emptyList())
        val marker = "___VPSH_${UUID.randomUUID()}___"
        return try {
            si.writeBytes("$command\n")
            si.writeBytes("echo $marker:$?\n")
            si.flush()
            val lines = mutableListOf<String>()
            var exitCode = -1
            while (true) {
                val line = so.readLine() ?: break
                if (line.startsWith(marker)) {
                    exitCode = line.substringAfter(":").trim().toIntOrNull() ?: -1
                    break
                }
                lines.add(line)
            }
            Result(exitCode, lines)
        } catch (_: Exception) {
            Result(-1, emptyList())
        }
    }

    fun close() = lock.withLock {
        try {
            stdin?.writeBytes("exit\n")
            stdin?.flush()
        } catch (_: Exception) { }
        try { process?.destroy() } catch (_: Exception) { }
        process = null
        stdin = null
        stdout = null
    }

    companion object {
        
        fun quickCheckRoot(): Boolean = try {
            val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            val finished = p.waitFor()
            finished == 0 && out.contains("uid=0")
        } catch (_: Exception) {
            false
        }
    }
}
