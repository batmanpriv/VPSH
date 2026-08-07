package com.batman.vpsh.data

object AppVersion {

    const val CURRENT = "3.6.3"

    private const val REPO_RAW_BASE = "https://raw.githubusercontent.com/batmanpriv/VPSH/main"
    private const val REPO_RELEASES_BASE = "https://github.com/batmanpriv/VPSH/releases/download"
    private const val REPO_RELEASES_PAGE = "https://github.com/batmanpriv/VPSH/releases"

    const val VERSION_CHECK_URL = "$REPO_RAW_BASE/version.txt"

    fun apkDownloadUrl(version: String): String = "$REPO_RELEASES_BASE/$version/VPSH.apk"

    fun releasePageUrl(version: String): String = "$REPO_RELEASES_PAGE/tag/$version"

    fun compare(a: String, b: String): Int {
        val pa = a.trim().removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val pb = b.trim().removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val len = maxOf(pa.size, pb.size)
        for (i in 0 until len) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }

    fun isNewer(remote: String, local: String = CURRENT): Boolean = compare(remote, local) > 0
}
