package com.kira.harmoid.hap

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/** Serializes file state changes both between threads and between Android processes. */
internal object HapFileLock {
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    fun <T> withLock(directory: File, blocking: Boolean = true, action: () -> T): T {
        val mutex = locks.computeIfAbsent(directory.canonicalPath) { ReentrantLock() }
        if (blocking) mutex.lock()
        else check(mutex.tryLock()) { "HAP files are busy; try again after installation completes" }
        try {
            if (!directory.isDirectory && !directory.mkdirs()) {
                throw IOException("Cannot create directory: $directory")
            }
            RandomAccessFile(File(directory, ".lock"), "rw").use { file ->
                val lock = if (blocking) file.channel.lock() else file.channel.tryLock()
                checkNotNull(lock) { "HAP files are busy; try again after installation completes" }
                lock.use { return action() }
            }
        } finally {
            mutex.unlock()
        }
    }
}

internal object HapPaths {
    fun validateNames(bundleName: String, moduleName: String) {
        require(bundleName.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*"))) {
            "Invalid HAP bundle name: $bundleName"
        }
        require(moduleName.matches(Regex("[A-Za-z0-9_-]+"))) {
            "Invalid HAP module name: $moduleName"
        }
    }

    fun resolve(root: File, path: String): File {
        // ZIP paths use '/'. Reject Windows separators and drive letters as well,
        // so validation has identical behavior on the host and on Android.
        require(path.isNotEmpty() && !path.startsWith('/') && '\\' !in path && ':' !in path) {
            "Invalid HAP entry path: $path"
        }
        require(path.split('/').none { it == "." || it == ".." }) {
            "HAP entry contains traversal components: $path"
        }
        val base = root.canonicalFile
        val target = File(base, path).canonicalFile
        require(target.toPath().startsWith(base.toPath()) && target != base) {
            "HAP entry escapes module directory: $path"
        }
        return target
    }
}

/** Stages a complete module before replacing an installation; keeps its app data. */
internal class HapInstallStorage(private val filesDir: File) {
    val baseDir = File(filesDir, "hap")

    fun recover() {
        // A killed installer can leave an unpublished staging tree. Every caller
        // holds the installation lock, so no live installer owns these trees.
        baseDir.listFiles()?.filter { it.isDirectory && it.name.startsWith(".install-") }
            ?.forEach {
                if (!it.deleteRecursively()) throw IOException("Cannot remove staging directory: $it")
            }
        baseDir.listFiles()?.filter { it.isDirectory && it.name.startsWith(".backup-") }
            ?.forEach { backup ->
                val target = File(baseDir, backup.name.removePrefix(".backup-"))
                if (target.exists()) {
                    if (!backup.deleteRecursively()) throw IOException("Cannot remove backup: $backup")
                } else if (!backup.renameTo(target)) {
                    throw IOException("Cannot restore module: $target")
                }
            }
    }

    // Caller holds the installation lock. Backups also recover an interrupted rename
    // on the next install/list operation after an Android process is killed.
    fun replace(fullModuleName: String, populate: (File) -> Unit): File {
        recover()
        val target = HapPaths.resolve(baseDir, fullModuleName)
        val backup = HapPaths.resolve(baseDir, ".backup-$fullModuleName")
        val staging = HapPaths.resolve(baseDir, ".install-${java.util.UUID.randomUUID()}")
        if (!staging.mkdirs()) throw IOException("Cannot create staging directory: $staging")
        try {
            populate(staging)
            for (name in listOf("files", "preference", "database")) {
                val data = File(target, name)
                if (data.exists()) data.copyRecursively(File(staging, name), overwrite = false)
            }
            // A failed cache removal must not publish new code with old runtime resources.
            val cached = HapPaths.resolve(File(filesDir, "sys"), fullModuleName)
            if (cached.exists() && !cached.deleteRecursively()) {
                throw IOException("Cannot invalidate runtime resources: $cached")
            }
            val hadTarget = target.exists()
            if (hadTarget && !target.renameTo(backup)) throw IOException("Cannot back up module: $target")
            if (!staging.renameTo(target)) {
                val failure = IOException("Cannot publish module: $target")
                if (hadTarget && !backup.renameTo(target)) {
                    failure.addSuppressed(IOException("Module backup retained at $backup"))
                }
                throw failure
            }
            // If cleanup fails the complete installation is still usable; recover()
            // retries removal on the next operation.
            if (hadTarget) backup.deleteRecursively()
            return target
        } finally {
            staging.deleteRecursively()
        }
    }
}
