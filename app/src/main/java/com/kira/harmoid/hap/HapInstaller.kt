package com.kira.harmoid.hap

import android.content.Context
import com.kira.harmoid.ProcessSlotManager
import com.kira.harmoid.ProcessSlotStore
import com.kira.harmoid.logging.LogWriter
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

data class InstalledHap(
    val bundleName: String,
    val moduleName: String,
    val contentDir: File,
    val moduleConfig: ModuleConfig,
    val mainAbility: String
)

class HapInstaller internal constructor(
    filesDir: File,
    private val cacheDir: File,
    private val patchLibrary: (File) -> Unit
) {
    constructor(context: Context) : this(context.filesDir, context.cacheDir, { soFile ->
        try {
            if (!ElfPatcher.patchSo(soFile.absolutePath)) {
                LogWriter.w(TAG, "ELF patch skipped or failed: ${soFile.name}")
            }
        } catch (e: UnsatisfiedLinkError) {
            LogWriter.w(TAG, "ELF patch JNI not available, skipping: ${soFile.name}", e)
        }
    })

    // Install to filesDir/hap/ so the ArkUI-X runtime's
    // StageAssetProvider.GetAppDataModuleDir() can discover them.
    private val storage = HapInstallStorage(filesDir)
    private val baseDir = storage.baseDir
    private val sysDir = File(filesDir, "sys")
    private val slots = ProcessSlotStore(File(filesDir, "process_slots"), ProcessSlotManager.MAX_SLOTS)

    fun install(hapPath: String): InstalledHap {
        val loader = HapBundleLoader()
        var unwrapped: File? = null
        try {
            val bundle = try {
                loader.parse(hapPath)
            } catch (e: HapParseException) {
                if (!loader.isAppPackage(hapPath)) throw e
                val hap = File(loader.unwrapSingleHap(hapPath))
                unwrapped = hap
                loader.parse(hap.path)
            }
            return HapFileLock.withLock(baseDir) { installFromBundle(bundle) }
        } finally {
            unwrapped?.delete()
        }
    }

    fun install(inputStream: InputStream): InstalledHap {
        val tmpFile = File.createTempFile("hap_install_", ".hap", cacheDir)
        try {
            tmpFile.outputStream().use { out -> inputStream.copyTo(out) }
            return install(tmpFile.absolutePath)
        } finally {
            tmpFile.delete()
        }
    }

    private fun installFromBundle(bundle: HapBundle): InstalledHap {
        val bundleName = bundle.moduleConfig.bundleName
        val moduleName = bundle.moduleConfig.name
        HapPaths.validateNames(bundleName, moduleName)
        ensureStopped(bundleName)
        // Runtime expects dot-separated: bundleName.moduleName
        val fullModuleName = "$bundleName.$moduleName"
        val installedDir = storage.replace(fullModuleName) { targetDir ->

            // Write module.json
            File(targetDir, "module.json").writeText(bundle.moduleConfig.rawJson)

            // Stream files directly from ZIP to disk to avoid OOM on large HAPs.
            ZipFile(bundle.hapFile).use { zip ->
                // .abc bytecode
                for (path in listOf("ets/modules.abc", "ets/modules_static.abc")) {
                    zip.getEntry(path)?.let { entry ->
                        val file = File(targetDir, path)
                        file.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            file.outputStream().use { out -> input.copyTo(out) }
                        }
                    }
                }

                // resources.index
                zip.getEntry("resources.index")?.let { entry ->
                    val file = File(targetDir, "resources.index")
                    zip.getInputStream(entry).use { input ->
                        file.outputStream().use { out -> input.copyTo(out) }
                    }
                }

                // Resource files under resources/
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name
                    if ((name.startsWith("resources/") || (name.startsWith("libs/") && name.endsWith(".so")))
                        && !entry.isDirectory) {
                        val file = HapPaths.resolve(targetDir, name)
                        file.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            file.outputStream().use { out -> input.copyTo(out) }
                        }
                    }
                }
            }

            // Extract pkgContextInfo.json from HAP (contains all npm dependency info)
            var pkgContextJson: String? = null
            ZipFile(bundle.hapFile).use { zip ->
                zip.getEntry("pkgContextInfo.json")?.let { entry ->
                    zip.getInputStream(entry).use { input ->
                        pkgContextJson = input.reader().readText()
                    }
                }
            }

            // Patch extracted .so files: replace DT_NEEDED "libc.so" with
            // "libb.so" in-place so the musl ABI bridge resolves first.
            val libsDir = File(targetDir, "libs")
            if (libsDir.isDirectory) {
                libsDir.walkTopDown()
                    .filter { it.isFile && it.extension == "so" }
                    .forEach(patchLibrary)
            }

            // Detect if HAP has native .so files
            val hasNativeLibs = File(targetDir, "libs").isDirectory &&
                File(targetDir, "libs").walkTopDown().any { it.isFile && it.extension == "so" }

            // Write pkgContextInfo.json (required by StageAssetProvider.GetPkgJsonBuffer)
            writePkgContextInfo(targetDir, fullModuleName, hasNativeLibs, pkgContextJson)
        }

        val mainAbility = bundle.moduleConfig.mainElement.ifBlank {
            bundle.moduleConfig.abilities.firstOrNull()?.name ?: ""
        }

        return InstalledHap(
            bundleName = bundleName,
            moduleName = moduleName,
            contentDir = installedDir,
            moduleConfig = bundle.moduleConfig,
            mainAbility = mainAbility
        )
    }

    private fun writePkgContextInfo(targetDir: File, fullModuleName: String, hasNativeLibs: Boolean, originalJson: String?) {
        // OHOS-format pkgContextInfo.json expected by js_runtime.cpp::ParsePkgContextInfoJson.
        // If the HAP ships its own pkgContextInfo.json, use it as the base and fill in
        // moduleName/bundleName for any entries that have them empty (npm dependencies).
        val lastDot = fullModuleName.lastIndexOf('.')
        val shortName = if (lastDot >= 0) fullModuleName.substring(lastDot + 1) else fullModuleName
        val bundleName = if (lastDot >= 0) fullModuleName.substring(0, lastDot) else ""

        val json = if (originalJson != null) {
            try {
                val orig = JSONObject(originalJson)
                for (key in orig.keys()) {
                    val item = orig.optJSONObject(key) ?: continue
                    if (item.optString("moduleName", "").isEmpty()) {
                        item.put("moduleName", fullModuleName)
                    }
                    if (item.optString("bundleName", "").isEmpty()) {
                        item.put("bundleName", bundleName)
                    }
                }
                orig
            } catch (e: Exception) {
                LogWriter.w(TAG, "Failed to parse HAP pkgContextInfo.json, generating fallback", e)
                null
            }
        } else null

        val finalJson = json ?: JSONObject().apply {
            put(shortName, JSONObject().apply {
                put("packageName", shortName)
                put("bundleName", bundleName)
                put("moduleName", fullModuleName)
                put("version", "")
                put("entryPath", "src/main/")
                put("isSO", hasNativeLibs)
                put("dependencyAlias", "")
            })
        }

        File(targetDir, "pkgContextInfo.json").writeText(finalJson.toString())
    }

    fun getInstalledHaps(): List<InstalledHap> {
        if (!baseDir.exists()) return emptyList()
        return HapFileLock.withLock(baseDir) {
            storage.recover()
            baseDir.listFiles()
                ?.filter { it.isDirectory && !it.name.startsWith('.') }
                ?.mapNotNull { moduleDir ->
                    // Directory name format: bundleName.moduleName
                    val dirName = moduleDir.name
                    val dotIndex = dirName.lastIndexOf('.')
                    if (dotIndex < 0) return@mapNotNull null

                    val bundleName = dirName.substring(0, dotIndex)
                    val moduleName = dirName.substring(dotIndex + 1)

                    val moduleJson = File(moduleDir, "module.json")
                    if (!moduleJson.exists()) return@mapNotNull null

                    val config = try {
                        HapBundleLoader().parseModuleJson(moduleJson.readText())
                    } catch (_: Exception) {
                        return@mapNotNull null
                    }

                    val mainAbility = config.mainElement.ifBlank {
                        config.abilities.firstOrNull()?.name ?: ""
                    }

                    InstalledHap(
                        bundleName = bundleName,
                        moduleName = moduleName,
                        contentDir = moduleDir,
                        moduleConfig = config,
                        mainAbility = mainAbility
                    )
                } ?: emptyList()
        }
    }

    companion object {
        private const val TAG = "Harmoid.HapInstaller"
    }

    fun uninstall(bundleName: String) {
        HapPaths.validateNames(bundleName, "entry")
        HapFileLock.withLock(baseDir) {
            ensureStopped(bundleName)
            storage.recover()
            // Module names cannot contain dots. Compare the full bundle name,
            // otherwise uninstalling com.example also deletes com.example.other.
            for (root in listOf(baseDir, sysDir)) {
                root.listFiles()
                    ?.filter { it.isDirectory && !it.name.startsWith('.') &&
                        it.name.substringBeforeLast('.', "") == bundleName }
                    ?.forEach {
                        if (!it.deleteRecursively()) throw java.io.IOException("Cannot uninstall module: $it")
                    }
            }
        }
    }

    private fun ensureStopped(bundleName: String) {
        check(slots.activeContentDirs().none { path ->
            val module = File(path).canonicalFile
            module.parentFile == baseDir.canonicalFile &&
                module.name.substringBeforeLast('.', "") == bundleName
        }) { "HAP $bundleName is running; close it before installing or uninstalling" }
    }
}
