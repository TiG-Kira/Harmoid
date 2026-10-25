package com.kira.harmoid.hap

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class HapBundleLoaderTest {

    private lateinit var testHap: File

    @Before
    fun setUp() {
        // test_app.hap is built from the module.json + .abc in assets
        val resourcePath = javaClass.classLoader
            ?.getResource("test_app.hap")?.toURI()
            ?: throw IllegalStateException("test_app.hap not found in test resources")
        testHap = File(resourcePath)
    }

    @Test
    fun parse_moduleConfig() {
        val bundle = HapBundleLoader().parse(testHap.absolutePath)

        val config = bundle.moduleConfig
        assertEquals("com.example.enjoyarkuix", config.bundleName)
        assertEquals("dynamicHap", config.name)
        assertEquals("feature", config.type)
        assertEquals("DynamicHapAbility", config.mainElement)
        assertEquals("ark12.0.6.0", config.virtualMachine)
    }

    @Test
    fun parse_abilities() {
        val bundle = HapBundleLoader().parse(testHap.absolutePath)

        val abilities = bundle.moduleConfig.abilities
        assertTrue("Should have at least one ability", abilities.isNotEmpty())

        val mainAbility = abilities.first()
        assertEquals("DynamicHapAbility", mainAbility.name)
        assertEquals("page", mainAbility.type)
        assertTrue(mainAbility.exported)
    }

    @Test
    fun parse_pages() {
        val bundle = HapBundleLoader().parse(testHap.absolutePath)

        val pages = bundle.moduleConfig.pages
        assertTrue("Should have at least one page", pages.isNotEmpty())
        // The test HAP has $profile:main_pages.json which contains pages/Index
        assertTrue(pages.any { it.contains("Index") })
    }

    @Test(expected = HapParseException::class)
    fun parse_invalidHap_throwsException() {
        // Create a temp file that's not a valid HAP
        val tmpFile = File.createTempFile("invalid", ".hap")
        tmpFile.deleteOnExit()
        tmpFile.writeText("not a zip file")

        HapBundleLoader().parse(tmpFile.absolutePath)
    }

    @Test(expected = HapParseException::class)
    fun parse_hapWithoutModuleJson_throwsException() {
        // Create a ZIP without module.json
        val tmpFile = File.createTempFile("no_module", ".hap")
        tmpFile.deleteOnExit()
        java.util.zip.ZipOutputStream(tmpFile.outputStream()).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("dummy.txt"))
            zos.write("hello".toByteArray())
            zos.closeEntry()
        }

        HapBundleLoader().parse(tmpFile.absolutePath)
    }

}
