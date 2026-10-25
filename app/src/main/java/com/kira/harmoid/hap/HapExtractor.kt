package com.kira.harmoid.hap

import android.content.Context
import android.util.Log
import java.io.File

/** The development HAP uses the same validation and installation as user HAPs. */
object HapExtractor {
    private const val TAG = "Harmoid.HapExtractor"

    fun extractHapToFilesDir(
        context: Context,
        hapAssetPath: String,
        bundleName: String,
        moduleName: String
    ): Boolean {
        var temporary: File? = null
        return try {
            HapPaths.validateNames(bundleName, moduleName)
            val hap = File.createTempFile("hap_asset_", ".hap", context.cacheDir)
            temporary = hap
            context.assets.open(hapAssetPath).use { input ->
                hap.outputStream().use { output -> input.copyTo(output) }
            }
            val config = HapBundleLoader().previewConfig(hap.absolutePath)
            require(config.bundleName == bundleName && config.name == moduleName) {
                "Bundled HAP identity does not match $bundleName.$moduleName"
            }
            HapInstaller(context).install(hap.absolutePath)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract HAP: $hapAssetPath", e)
            false
        } finally {
            temporary?.delete()
        }
    }
}
