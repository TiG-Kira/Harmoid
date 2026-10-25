package com.kira.harmoid.compat

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.BatteryManager
import android.os.Looper
import android.graphics.Bitmap
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothAdapter
import com.kira.harmoid.ui.AboutBgEffect
import ohos.ace.plugin.bluetoothplugin.BluetoothGattClient
import ohos.ace.plugin.locationserviceplugin.LocationServicePlugin
import com.kira.harmoid.hap.HapBundleLoader
import com.kira.harmoid.bridge.ActivityTracker
import ohos.ace.plugin.broadcastplugin.BroadcastPlugin
import ohos.ace.plugin.wifimanager.WifiBroadcastReceiver
import ohos.ace.plugin.wifimanager.WifiBroadcastInterface
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.shadows.ShadowBluetoothGatt
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 34], application = Application::class, manifest = Config.NONE)
class AndroidCompatibilityTest {
    @Implements(BluetoothGatt::class)
    class DeniedGatt : ShadowBluetoothGatt() {
        @Implementation override fun disconnect() { throw SecurityException("Permission revoked") }
        @Implementation override fun close() { throw SecurityException("Permission revoked") }
        @Implementation override fun discoverServices(): Boolean { throw SecurityException("Permission revoked") }
    }

    private class TestLocation(context: Context) : LocationServicePlugin(context) {
        override fun nativeInit() { }
    }
    private class RecordingBroadcast(context: Context) : BroadcastPlugin(context, false) {
        val events = mutableListOf<Pair<String, String>>()
        override fun nativeReceiveBroadcast(key: String, action: String, json: String) {
            events.add(key to action)
        }
    }

    @Test fun resolvesCurrentActivityWithoutHiddenReflectionAndReleasesIt() {
        val app = RuntimeEnvironment.getApplication()
        ActivityTracker.initialize(app)
        val controller = Robolectric.buildActivity(Activity::class.java).create().start().resume()
        try {
            assertSame(controller.get(), ActivityTracker.getActivity(app))
            assertSame(controller.get(), ActivityTracker.getActivity(ContextWrapper(controller.get())))
            controller.pause().stop().destroy()
            assertNull(ActivityTracker.getActivity(app))
        } finally {
            if (!controller.get().isDestroyed) controller.pause().stop().destroy()
        }
    }

    @Test fun replacesBroadcastSubscriptionsAndUnregistersIdempotently() {
        val app = RuntimeEnvironment.getApplication()
        val plugin = RecordingBroadcast(app)
        val action = "com.kira.harmoid.TEST_EVENT"
        assertTrue(plugin.registerBroadcast("key", arrayOf(action)))
        assertTrue(plugin.registerBroadcast("key", arrayOf(action)))
        app.sendBroadcast(Intent(action))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("key" to action), plugin.events)
        assertTrue(plugin.unRegisterBroadcast("key"))
        assertTrue(plugin.unRegisterBroadcast("key"))
        app.sendBroadcast(Intent(action))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, plugin.events.size)
    }

    @Test fun batteryStateIsIndependentForEachSubscriberAndRejectsInvalidScale() {
        val app = RuntimeEnvironment.getApplication()
        val plugin = RecordingBroadcast(app)
        assertTrue(plugin.registerBroadcast("one", arrayOf(Intent.ACTION_BATTERY_LOW)))
        assertTrue(plugin.registerBroadcast("two", arrayOf(Intent.ACTION_BATTERY_LOW)))
        fun battery(scale: Int) {
            app.sendBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED)
                .putExtra(BatteryManager.EXTRA_LEVEL, 10).putExtra(BatteryManager.EXTRA_SCALE, scale))
            shadowOf(Looper.getMainLooper()).idle()
        }
        battery(0)
        assertTrue(plugin.events.isEmpty())
        battery(100)
        assertEquals(setOf("one", "two"), plugin.events.map { it.first }.toSet())
        battery(100)
        assertEquals(2, plugin.events.size)
        plugin.unRegisterBroadcast("one")
        plugin.unRegisterBroadcast("two")
    }

    @Test fun wifiReceiversCanBeRegisteredAndRemovedRepeatedly() {
        val callbacks = object : WifiBroadcastInterface {
            override fun wifiConnectState(state: Int) { }
            override fun wifiSwitchState(state: Int) { }
        }
        val receiver = WifiBroadcastReceiver(RuntimeEnvironment.getApplication(), callbacks)
        receiver.unRegisterConnectReceiver()
        receiver.unRegisterSwitchReceiver()
        repeat(2) {
            receiver.registerConnectReceiver()
            receiver.registerConnectReceiver()
            receiver.registerSwitchReceiver()
            receiver.registerSwitchReceiver()
            receiver.unRegisterConnectReceiver()
            receiver.unRegisterConnectReceiver()
            receiver.unRegisterSwitchReceiver()
            receiver.unRegisterSwitchReceiver()
        }
    }

    @Test fun namedIconsWithoutNumericIdsLoadWithBoundedBitmapMemory() {
        val module = File(RuntimeEnvironment.getApplication().cacheDir, "icon-module").apply { mkdirs() }
        val image = File(module, "resources/base/media/large.png").apply { parentFile?.mkdirs() }
        val source = Bitmap.createBitmap(2048, 1024, Bitmap.Config.ARGB_8888)
        image.outputStream().use { assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        source.recycle()
        val json = """{"app":{"bundleName":"com.example.icons","icon":"${'$'}media:large"},
            "module":{"name":"entry","type":"entry"}}"""
        val config = HapBundleLoader().parseModuleJson(json)
        val result = checkNotNull(HapBundleLoader.loadHapIcon(module, config))
        assertTrue(result.width <= 512 && result.height <= 512)
        result.recycle()
    }

    @Test @Config(shadows = [DeniedGatt::class])
    fun revokedGattPermissionReturnsAnApiErrorInsteadOfCrashing() {
        val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice("00:11:22:33:44:55")
        val gatt = ShadowBluetoothGatt.newInstance(device)
        val client = BluetoothGattClient(1)
        ReflectionHelpers.setField(client, "bluetoothGatt_", gatt)
        assertEquals(201, client.disconnect())
        assertEquals(201, client.close())
        assertEquals(201, client.discoverServices())
    }

    @Test @Config(sdk = [31])
    fun android12DoesNotTryToLoadAnAndroid13RuntimeShader() {
        assertNull(AboutBgEffect.createFor(android.view.View(RuntimeEnvironment.getApplication()), false))
    }

    @Test fun aLocationQueryNeverBlocksTheUiThreadWaitingForItsOwnCallbacks() {
        val plugin = TestLocation(RuntimeEnvironment.getApplication())
        val start = System.nanoTime()
        assertNull(plugin.currentLocation)
        assertTrue("UI thread blocked while waiting for GPS", System.nanoTime() - start < 1_000_000_000L)
    }
}
