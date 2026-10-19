package com.kira.harmoid

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.kira.harmoid.logging.LogWriter
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 更新检查：查 Harmoid 自己的 GitHub Release（TiG-Kira/Harmoid）。
 *
 * 注意：仓库当前是 **private**，匿名访问 GitHub releases API 会 404
 * （Result.Error 路径会提示"检查失败"）。等仓库转公开，或配置一个
 * 只读 token 加进请求头后即可正常检查。
 */
object UpdateChecker {

    private const val TAG = "Harmoid.UpdateChecker"
    private const val RELEASES_URL = "https://api.github.com/repos/TiG-Kira/Harmoid/releases"
    private const val DOWNLOAD_URL = "https://github.com/TiG-Kira/Harmoid/releases"

    sealed class Result {
        data class UpdateAvailable(val date: String) : Result()
        object UpToDate : Result()
        data class Error(val message: String) : Result()
    }

    /** 关于页头部标记用的最小 release 元信息。 */
    data class ReleaseMeta(
        val tagName: String,
        val isPrerelease: Boolean,
        val date: java.util.Date?,
    )

    fun check(context: Context, onResult: (Result) -> Unit) {
        Thread {
            val result: Result = try {
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                @Suppress("DEPRECATION")
                val localCode = info.versionCode
                val localName = info.versionName ?: ""

                val latest = fetchLatestRelease()
                if (latest == null) {
                    Result.Error("Cannot fetch release info")
                } else {
                    LogWriter.i(TAG, "Local: $localName (code=$localCode), latest: ${latest.name} (code=${latest.versionCode})")

                    val hasUpdate = if (latest.versionCode != null) {
                        latest.versionCode > localCode
                    } else {
                        // Fallback: compare dates at day granularity, not exact time.
                        // This avoids false positives when release is same day as local build.
                        val currentDate = parseVersionDate(localName)
                        currentDate != null && latest.date != null &&
                            latest.date.after(currentDate) &&
                            !isSameDay(latest.date, currentDate)
                    }

                    if (hasUpdate) {
                        val dateStr = latest.date?.let {
                            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(it)
                        } ?: latest.name
                        Result.UpdateAvailable(dateStr)
                    } else {
                        Result.UpToDate
                    }
                }
            } catch (e: Exception) {
                LogWriter.w(TAG, "Update check failed", e)
                Result.Error(e.message ?: "Unknown error")
            }

            android.os.Handler(context.mainLooper).post {
                onResult(result)
            }
        }.start()
    }

    fun showResult(context: Context, result: Result) {
        when (result) {
            is Result.UpdateAvailable -> {
                AlertDialog.Builder(context)
                    .setTitle(context.getString(R.string.update_dialog_title))
                    .setMessage(context.getString(R.string.update_dialog_msg, result.date))
                    .setPositiveButton(context.getString(R.string.update_dialog_go)) { _, _ ->
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_URL)))
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            is Result.UpToDate -> {
                AlertDialog.Builder(context)
                    .setTitle(context.getString(R.string.update_dialog_title))
                    .setMessage(context.getString(R.string.update_dialog_uptodate))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            is Result.Error -> {
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.update_check_failed, result.message),
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * 拉最新 release 的元信息（tag / 是否 prerelease / 发布时间）。
     * 供关于页头部显示 Beta / 内测标记。失败返回 null（调用方按"内测构建"展示）。
     */
    fun fetchReleaseMeta(): ReleaseMeta? {
        return try {
            val conn = URL(RELEASES_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "Harmoid-UpdateChecker")

            if (conn.responseCode != 200) {
                LogWriter.w(TAG, "API returned ${conn.responseCode}")
                return null
            }

            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val releases = JSONArray(body)
            if (releases.length() == 0) return null
            val obj = releases.getJSONObject(0)
            val dateStr = (obj.optString("published_at").ifEmpty {
                obj.optString("created_at")
            }).take(19)
            val date = try {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse(dateStr)
            } catch (_: Exception) { null }
            ReleaseMeta(
                tagName = obj.optString("tag_name"),
                isPrerelease = obj.optBoolean("prerelease", false),
                date = date,
            )
        } catch (e: Exception) {
            LogWriter.w(TAG, "fetchReleaseMeta failed", e)
            null
        }
    }

    private data class ReleaseInfo(
        val name: String,
        val versionCode: Int?,
        val date: java.util.Date?
    )

    private fun fetchLatestRelease(): ReleaseInfo? {
        return try {
            val dateParser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            val conn = URL(RELEASES_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "Harmoid-UpdateChecker")

            if (conn.responseCode != 200) {
                LogWriter.w(TAG, "API returned ${conn.responseCode}")
                return null
            }

            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            val releases = JSONArray(body)
            if (releases.length() == 0) return null
            val list = mutableListOf<ReleaseInfo>()
            for (i in 0 until releases.length()) {
                val obj = releases.getJSONObject(i)
                val name = obj.optString("name").ifEmpty {
                    obj.optString("tag_name")
                }
                if (name.isEmpty()) continue
                val dateStr = (obj.optString("published_at").ifEmpty {
                    obj.optString("created_at")
                }).take(19)
                val date = dateParser.parse(dateStr) ?: continue

                // Try name first, then tag_name for version code (both may have prefix)
                var code = parseVersionCode(name)
                if (code == null) {
                    val tagName = obj.optString("tag_name")
                    if (tagName.isNotEmpty()) {
                        code = parseVersionCode(tagName)
                    }
                }

                list.add(ReleaseInfo(name = name, versionCode = code, date = date))
            }

            if (list.isEmpty()) return null

            // Sort by versionCode first (when available), then by date
            list.sortWith(compareByDescending<ReleaseInfo> { it.versionCode ?: 0 }
                .thenByDescending { it.date })
            list[0]
        } catch (_: Exception) {
            null
        }
    }

    // Parse versionName "YY.M.D.B" → versionCode (YYMMDDBB).
    // Returns null if the string doesn't match the expected format.
    private fun parseVersionCode(versionName: String): Int? {
        return try {
            val parts = versionName.split(".")
            if (parts.size < 4) return null
            val yy = parts[0].toInt()
            val mm = parts[1].toInt()
            val dd = parts[2].toInt()
            val bb = parts[3].toInt()
            yy * 10000000 + mm * 100000 + dd * 1000 + bb
        } catch (_: Exception) {
            null
        }
    }

    private fun parseVersionDate(versionName: String): java.util.Date? {
        return try {
            val parts = versionName.split(".")
            if (parts.size < 3) return null
            val year = 2000 + parts[0].toInt()
            val month = parts[1].toInt()
            val day = parts[2].toInt()
            SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(
                "$year-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"
            )
        } catch (_: Exception) {
            null
        }
    }

    // Compare two dates at day granularity, ignoring time-of-day.
    private fun isSameDay(a: java.util.Date, b: java.util.Date): Boolean {
        val calA = java.util.Calendar.getInstance().apply { time = a }
        val calB = java.util.Calendar.getInstance().apply { time = b }
        return calA.get(java.util.Calendar.YEAR) == calB.get(java.util.Calendar.YEAR) &&
            calA.get(java.util.Calendar.DAY_OF_YEAR) == calB.get(java.util.Calendar.DAY_OF_YEAR)
    }
}
