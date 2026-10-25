package com.kira.harmoid

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.io.File

class ProcessSlotStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun concurrentAllocationsAcrossStoreInstancesNeverShareSlots() {
        val dir = temporary.newFolder()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = pool.invokeAll((0 until 40).map { index -> Callable {
                ProcessSlotStore(dir, 10).allocate("module-$index")
            } }).map { it.get() }.filter { it >= 0 }
            assertEquals(10, results.size)
            assertEquals(10, results.toSet().size)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun expiredReservationAndDeadProcessCanBeReused() {
        var clock = 1000L
        var alive = true
        val store = ProcessSlotStore(temporary.newFolder(), 1, { clock }, { alive })
        assertEquals(0, store.allocate("module"))
        assertEquals(-1, store.allocate("module"))
        clock += 30_001
        assertTrue(store.activeContentDirs().isEmpty())
        assertEquals("module", store.contentDir(0))
        assertEquals(0, store.allocate("module"))
        store.claim(0, 123, "module")
        clock += 60_000
        assertEquals(-1, store.allocate("other"))
        alive = false
        assertEquals(0, store.allocate("other"))
    }

    @Test fun cannotReleaseOrOverwriteAnotherLiveOwner() {
        val store = ProcessSlotStore(temporary.newFolder(), 1, { 1000 }, { true })
        store.allocate("module")
        store.claim(0, 123, "module")
        store.release(0, 456)
        assertEquals("module", store.contentDir(0))
        assertThrows(IllegalStateException::class.java) { store.claim(0, 456, "module") }
        store.claim(0, 123, "module") // configuration recreation keeps the same owner
        store.release(0, 123)
        assertEquals(0, store.allocate("next"))
    }

    @Test fun rejectsMismatchingReservationAndInvalidSlot() {
        val store = ProcessSlotStore(temporary.newFolder(), 1)
        store.allocate("module")
        assertThrows(IllegalStateException::class.java) { store.claim(0, 123, "other") }
        assertThrows(IllegalArgumentException::class.java) { store.claim(-1, 123, "module") }
    }

    @Test fun separateJvmProcessesNeverAllocateTheSameSlot() {
        val dir = temporary.newFolder()
        val classpath = listOf(ProcessSlotWorker::class.java, ProcessSlotStore::class.java,
            kotlin.Unit::class.java).map { File(it.protectionDomain.codeSource.location.toURI()).path }
            .distinct().joinToString(File.pathSeparator)
        val java = File(System.getProperty("java.home"), "bin/java").path
        val workers = (0 until 4).map {
            ProcessBuilder(java, "-cp", classpath, ProcessSlotWorker::class.java.name, dir.path)
                .redirectErrorStream(true).start()
        }
        try {
            val allocated = workers.flatMap { process ->
                assertTrue("Worker timed out", process.waitFor(15, TimeUnit.SECONDS))
                val output = process.inputStream.bufferedReader().readText()
                assertEquals(output, 0, process.exitValue())
                output.lineSequence().mapNotNull { it.toIntOrNull() }.filter { it >= 0 }.toList()
            }
            assertEquals(10, allocated.size)
            assertEquals(10, allocated.toSet().size)
        } finally {
            workers.filter { it.isAlive }.forEach { it.destroyForcibly() }
        }
    }
}

object ProcessSlotWorker {
    @JvmStatic fun main(args: Array<String>) {
        val store = ProcessSlotStore(File(args[0]), 10)
        repeat(5) { println(store.allocate("module-${ProcessHandle.current().pid()}")) }
    }
}
