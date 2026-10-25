package com.kira.harmoid.hap

import com.kira.harmoid.ProcessSlotStore
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HapInstallerTest {
    @get:Rule val temporary = TemporaryFolder()
    private val files by lazy { temporary.newFolder("files") }
    private val cache by lazy { temporary.newFolder("cache") }
    private val installer by lazy { HapInstaller(files, cache) {} }

    private fun hap(
        bundle: String = "com.example.app",
        module: String = "entry",
        entries: Map<String, String> = mapOf("ets/modules.abc" to "bytecode"),
        moduleOnly: Boolean = false
    ): File {
        val config = JSONObject().put("module", JSONObject()
            .put("name", module).put("type", "entry").put("mainElement", "EntryAbility"))
        if (!moduleOnly) config.put("app", JSONObject().put("bundleName", bundle))
        return temporary.newFile().also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                val contents = mutableMapOf("module.json" to config.toString())
                contents.putAll(entries)
                if (moduleOnly) contents["pack.info"] = """
                    {"summary":{"app":{"bundleName":"$bundle","version":{"code":42,"name":"2.0"}}}}
                """.trimIndent()
                for ((path, value) in contents) {
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(value.toByteArray())
                    zip.closeEntry()
                }
            }
        }
    }

    @Test fun installsResourcesAndPatchesNativeLibraries() {
        val patched = mutableListOf<String>()
        val installed = HapInstaller(files, cache) { patched.add(it.name) }.install(hap(entries = mapOf(
            "ets/modules.abc" to "abc", "resources/rawfile/data.txt" to "data",
            "libs/arm64-v8a/libentry.so" to "elf"
        )).path)
        assertEquals("data", File(installed.contentDir, "resources/rawfile/data.txt").readText())
        assertEquals(listOf("libentry.so"), patched)
        val info = JSONObject(File(installed.contentDir, "pkgContextInfo.json").readText())
        assertTrue(info.getJSONObject("entry").getBoolean("isSO"))
    }

    @Test fun upgradePreservesDataRemovesOldCodeAndInvalidatesRuntimeCache() {
        val installed = installer.install(hap(entries = mapOf("resources/old.txt" to "old")).path)
        for (dir in listOf("files", "preference", "database")) {
            File(installed.contentDir, "$dir/user-data").apply { parentFile?.mkdirs(); writeText(dir) }
        }
        val sys = File(files, "sys/com.example.app.entry").apply { mkdirs() }
        File(sys, "resources.index").writeText("old")
        val upgraded = installer.install(hap().path)
        assertFalse(File(upgraded.contentDir, "resources/old.txt").exists())
        assertEquals("bytecode", File(upgraded.contentDir, "ets/modules.abc").readText())
        for (dir in listOf("files", "preference", "database")) {
            assertEquals(dir, File(upgraded.contentDir, "$dir/user-data").readText())
        }
        assertFalse(sys.exists())
    }

    @Test fun traversalCannotOverwriteOtherModulesAndKeepsInstalledVersion() {
        val installed = installer.install(hap().path)
        val sentinel = File(files, "hap/victim").apply { writeText("keep") }
        val bad = hap(entries = mapOf("resources/../../victim" to "corrupt"))
        assertThrows(IllegalArgumentException::class.java) { installer.install(bad.path) }
        assertEquals("keep", sentinel.readText())
        assertEquals("bytecode", File(installed.contentDir, "ets/modules.abc").readText())
        assertEquals(1, installer.getInstalledHaps().size)
    }

    @Test fun nativePatchFailureDoesNotDestroyOldInstallation() {
        val installed = installer.install(hap().path)
        val failing = HapInstaller(files, cache) { throw IOException("patch failed") }
        assertThrows(IOException::class.java) {
            failing.install(hap(entries = mapOf("libs/arm64-v8a/libentry.so" to "elf")).path)
        }
        assertEquals("bytecode", File(installed.contentDir, "ets/modules.abc").readText())
    }

    @Test fun rejectsInvalidBundleAndModuleNames() {
        for ((bundle, module) in listOf("../victim" to "entry", "" to "entry",
            "com.example.app" to "../../victim", "com.example.app" to "entry.more")) {
            assertThrows(IllegalArgumentException::class.java) { installer.install(hap(bundle, module).path) }
        }
    }

    @Test fun uninstallMatchesExactBundle() {
        installer.install(hap(bundle = "com.example").path)
        installer.install(hap(bundle = "com.example.other").path)
        installer.uninstall("com.example")
        assertEquals(listOf("com.example.other"), installer.getInstalledHaps().map { it.bundleName })
    }

    @Test fun moduleOnlyConfigResolvesAndPersistsPackIdentity() {
        val file = hap(moduleOnly = true)
        assertFalse(HapBundleLoader().isAppPackage(file.path))
        val preview = HapBundleLoader().previewConfig(file.path)
        assertEquals("com.example.app", preview.bundleName)
        assertEquals(42, preview.versionCode)
        assertEquals("2.0", preview.versionName)
        installer.install(file.path)
        assertEquals(preview.bundleName, installer.getInstalledHaps().single().moduleConfig.bundleName)
    }

    @Test fun recoversInstallationInterruptedBetweenRenames() {
        val installed = installer.install(hap().path)
        val backup = File(files, "hap/.backup-com.example.app.entry")
        assertTrue(installed.contentDir.renameTo(backup))
        val orphan = File(files, "hap/.install-interrupted").apply { mkdirs() }
        File(orphan, "incomplete").writeText("interrupted installation")
        assertEquals(1, installer.getInstalledHaps().size)
        assertTrue(File(installed.contentDir, "ets/modules.abc").isFile)
        assertFalse(backup.exists())
        assertFalse(orphan.exists())
    }

    @Test fun pathValidationRejectsWindowsAndAbsolutePaths() {
        for (path in listOf("/etc/passwd", "C:/outside", "resources\\..\\outside", "../outside",
            "resources/../module.json")) {
            assertThrows(IllegalArgumentException::class.java) { HapPaths.resolve(files, path) }
        }
    }

    @Test fun runningOrReservedBundleCannotBeUpdatedOrUninstalled() {
        val installed = installer.install(hap().path)
        val slots = ProcessSlotStore(File(files, "process_slots"), 10)
        val slot = slots.allocate(installed.contentDir.absolutePath)
        assertThrows(IllegalStateException::class.java) { installer.install(hap().path) }
        assertThrows(IllegalStateException::class.java) { installer.uninstall("com.example.app") }
        assertTrue(File(installed.contentDir, "ets/modules.abc").exists())
        // A different bundle remains installable while this one runs.
        installer.install(hap(bundle = "com.example.other").path)
        slots.release(slot, 123)
        installer.uninstall("com.example.app")
        assertEquals(listOf("com.example.other"), installer.getInstalledHaps().map { it.bundleName })
    }

    @Test fun launchLockCanFailWithoutBlockingOnInstallation() {
        val directory = temporary.newFolder()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val task = executor.submit {
            HapFileLock.withLock(directory) {
                entered.countDown()
                assertTrue(release.await(10, TimeUnit.SECONDS))
            }
        }
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            assertThrows(IllegalStateException::class.java) {
                HapFileLock.withLock(directory, blocking = false) { fail("Installation owns this lock") }
            }
        } finally {
            release.countDown()
            executor.shutdown()
        }
        task.get(10, TimeUnit.SECONDS)
    }

    @Test fun directInstallerSupportsSingleHapAppPackages() {
        val source = hap()
        val app = temporary.newFile()
        ZipOutputStream(app.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("pack.info"))
            zip.write("{}".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("entry.hap"))
            source.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
        assertEquals("com.example.app", installer.install(app.path).bundleName)
    }

    @Test fun malformedModuleJsonProducesActionableParseError() {
        val invalid = hap(entries = mapOf("module.json" to "not JSON"))
        assertThrows(HapParseException::class.java) { HapBundleLoader().previewConfig(invalid.path) }
        assertThrows(HapParseException::class.java) { installer.install(invalid.path) }
    }
}
