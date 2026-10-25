package com.kira.harmoid.hdc

import java.io.File

/** Maps the OHOS development transfer directory into the host cache. */
internal object HdcPaths {
    fun resolve(cacheDir: File, path: String): File {
        require(path.isNotBlank() && '\\' !in path && ':' !in path && '\u0000' !in path) { "Invalid HDC path" }
        require(path.split('/').none { it == "." || it == ".." }) { "HDC path contains traversal components" }
        val root = File(cacheDir, "hdc").canonicalFile
        val normalized = path.trimStart('/')
        val target = when {
            normalized == "data/local/tmp" -> root
            normalized.startsWith("data/local/tmp/") -> File(root, normalized.removePrefix("data/local/tmp/"))
            path.startsWith('/') || File(path).isAbsolute -> File(path)
            else -> File(root, path)
        }.canonicalFile
        require(target.toPath().startsWith(root.toPath())) { "HDC transfer path is outside the development directory" }
        return target
    }

    fun child(directory: File, name: String): File {
        require(name.isNotBlank() && '\u0000' !in name) { "Invalid HDC filename" }
        return com.kira.harmoid.hap.HapPaths.resolve(directory, name)
    }
}
