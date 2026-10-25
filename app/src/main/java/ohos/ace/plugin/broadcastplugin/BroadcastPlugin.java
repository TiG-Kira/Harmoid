/*
 * Copyright (c) 2023-2026 Huawei Device Co., Ltd.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ohos.ace.plugin.broadcastplugin;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Bundle;
import android.text.TextUtils;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Iterator;
import java.util.Map;

import ohos.ace.adapter.ALog;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * BroadcastPlugin
 *
 * @since 1
 */
public class BroadcastPlugin {
    private static final String LOG_TAG = "BroadcastPlugin";
    private static final String JSON_BUNDLE_NAME = "bundleName";
    private static final String JSON_CODE = "code";
    private static final String JSON_DATA = "data";
    private static final String JSON_PARAMETERS = "parameters";
    private static final String EXTRA_BUNDLE_NAME = "common_event_bundle_name";
    private static final String EXTRA_CODE = "common_event_code";
    private static final String EXTRA_DATA = "common_event_data";
    private static final String EXTRA_PARAMETERS = "common_event_parameters";

    private static final int BATTERY_LOW_VALUE = 20;

    private static final int BATTERY_FULL_VALUE = 100;

    private Context context;

    private Map<String, BroadcastReceiver> broadcastReceiverMap;

    /**
     * BroadcastPlugin
     *
     * @param context context of the application
     */
    public BroadcastPlugin(Context context) {
        if (context != null) {
            this.context = context;
        } else {
            ALog.e(LOG_TAG, "context is null");
        }
        this.broadcastReceiverMap = new ConcurrentHashMap<>();
        nativeInit();
    }

    /**
     * BroadcastPlugin
     *
     * @param context context of the application
     * @param isNativeInit call nativeInit or not
     */
    public BroadcastPlugin(Context context, boolean isNativeInit) {
        if (context != null) {
            this.context = context;
        } else {
            ALog.e(LOG_TAG, "context is null");
        }
        this.broadcastReceiverMap = new ConcurrentHashMap<>();
        if (isNativeInit) {
            nativeInit();
        }
    }

    /**
     * sendBroadcast send broadcast
     *
     * @param action action of the broadcast
     * @param json json of the broadcast, supports only four reserved fields:
     *             "bundleName" (String), "code" (Number),
     *             "data" (String), "parameters" (JSONObject).
     *             Any other top-level keys are ignored.
     *             Use "parameters" to pass custom key-value pairs.
     * @return true if send broadcast success, false if send broadcast failed
     */
    public boolean sendBroadcast(String action, String json) {
        if (context == null) {
            return false;
        }
        if (TextUtils.isEmpty(action)) {
            return false;
        }
        Intent intent = new Intent(action);
        if (!TextUtils.isEmpty(json) && !appendPublishPayload(intent, json)) {
            return false;
        }
        context.sendBroadcast(intent);
        return true;
    }

    // Only handles the four reserved fields: bundleName, code, data, parameters.
    // Other top-level keys in the JSON are intentionally ignored.
    private boolean appendPublishPayload(Intent intent, String json) {
        try {
            JSONObject payload = new JSONObject(json);
            String bundleName = payload.optString(JSON_BUNDLE_NAME, "");
            setTargetBundle(intent, bundleName);
            putBundleNameExtra(intent, bundleName);
            return putCodeExtra(intent, payload)
                && putDataExtra(intent, payload)
                && putParametersExtra(intent, payload);
        } catch (JSONException e) {
            ALog.e(LOG_TAG, "Send broadcast failed, JSONException.");
            return false;
        }
    }

    private void setTargetBundle(Intent intent, String bundleName) {
        if (!TextUtils.isEmpty(bundleName)) {
            Intent result = intent.setPackage(bundleName);
            if (result == null) {
                ALog.w(LOG_TAG, "Failed to set target bundle, check if bundleName is valid: " + bundleName);
            }
            ALog.d(LOG_TAG, "Broadcast targeted to bundle: " + bundleName);
        }
    }

    private void putBundleNameExtra(Intent intent, String bundleName) {
        if (!TextUtils.isEmpty(bundleName)) {
            intent.putExtra(EXTRA_BUNDLE_NAME, bundleName);
        }
    }

    private boolean putCodeExtra(Intent intent, JSONObject payload) throws JSONException {
        if (!payload.has(JSON_CODE)) {
            return true;
        }
        Object value = payload.get(JSON_CODE);
        if (!(value instanceof Number)) {
            ALog.e(LOG_TAG, "code is not a number");
            return false;
        }
        intent.putExtra(EXTRA_CODE, ((Number) value).intValue());
        return true;
    }

    private boolean putDataExtra(Intent intent, JSONObject payload) throws JSONException {
        if (!payload.has(JSON_DATA)) {
            return true;
        }
        Object value = payload.get(JSON_DATA);
        if (!(value instanceof String)) {
            ALog.e(LOG_TAG, "data is not a string");
            return false;
        }
        intent.putExtra(EXTRA_DATA, (String) value);
        return true;
    }

    private boolean putParametersExtra(Intent intent, JSONObject payload) throws JSONException {
        if (!payload.has(JSON_PARAMETERS)) {
            return true;
        }
        Object value = payload.get(JSON_PARAMETERS);
        if (!(value instanceof JSONObject)) {
            ALog.e(LOG_TAG, "parameters is not a JSONObject");
            return false;
        }
        intent.putExtra(EXTRA_PARAMETERS, value.toString());
        return true;
    }

    /**
     * registerBroadcast register broadcast
     *
     * @param key key of the broadcast
     * @param actions actions of the broadcast
     * @return true if register broadcast success, false if register broadcast failed
     */
    public synchronized boolean registerBroadcast(String key, String[] actions) {
        if (context == null || TextUtils.isEmpty(key) || actions == null || actions.length == 0) return false;
        IntentFilter filter = new IntentFilter();
        boolean low = false, okay = false;
        for (String action : actions) {
            if (TextUtils.isEmpty(action)) return false;
            if (Intent.ACTION_BATTERY_LOW.equals(action)) low = true;
            if (Intent.ACTION_BATTERY_OKAY.equals(action)) okay = true;
            filter.addAction(Intent.ACTION_BATTERY_LOW.equals(action) || Intent.ACTION_BATTERY_OKAY.equals(action)
                    ? Intent.ACTION_BATTERY_CHANGED : action);
        }
        final boolean wantsLow = low, wantsOkay = okay;
        if (!unRegisterBroadcast(key)) return false;
        BroadcastReceiver receiver = new BroadcastReceiver() {
            private Boolean lastBatteryLow;
            @Override public void onReceive(Context receiverContext, Intent intent) {
                if (intent == null || broadcastReceiverMap.get(key) != this) return;
                String action = intent.getAction();
                if (action == null) return;
                if (Intent.ACTION_BATTERY_CHANGED.equals(action) && (wantsLow || wantsOkay)) {
                    int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                    int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 0);
                    if (scale <= 0 || level < 0) return;
                    boolean isLow = (long) level * BATTERY_FULL_VALUE / scale <= BATTERY_LOW_VALUE;
                    if (lastBatteryLow != null && lastBatteryLow == isLow) return;
                    lastBatteryLow = isLow;
                    if (isLow && wantsLow) action = Intent.ACTION_BATTERY_LOW;
                    else if (!isLow && wantsOkay) action = Intent.ACTION_BATTERY_OKAY;
                    else return;
                }
                try {
                    nativeReceiveBroadcast(key, action, buildReceivePayload(intent));
                } catch (JSONException e) {
                    ALog.e(LOG_TAG, "Receive broadcast failed: " + e.getMessage());
                }
            }
        };
        // Publish before registration: Android may deliver a sticky broadcast immediately.
        broadcastReceiverMap.put(key, receiver);
        try {
            androidx.core.content.ContextCompat.registerReceiver(context, receiver, filter,
                    androidx.core.content.ContextCompat.RECEIVER_EXPORTED);
            return true;
        } catch (IllegalArgumentException | SecurityException e) {
            broadcastReceiverMap.remove(key, receiver);
            ALog.e(LOG_TAG, "Register broadcast failed: " + e.getMessage());
            return false;
        }
    }

    private String buildReceivePayload(Intent intent) throws JSONException {
        JSONObject payload = new JSONObject();
        Bundle bundle = intent.getExtras();
        JSONObject parameters = readParameters(bundle);
        appendBundleValues(bundle, payload, parameters);
        setBundleNameIfNeeded(payload, bundle, intent);
        return finalizePayload(payload, parameters);
    }

    private void setBundleNameIfNeeded(JSONObject payload, Bundle bundle, Intent intent) throws JSONException {
        if (payload.has(JSON_BUNDLE_NAME)) {
            return;
        }
        String bundleName = resolveBundleName(bundle, intent);
        if (!TextUtils.isEmpty(bundleName)) {
            payload.put(JSON_BUNDLE_NAME, bundleName);
        }
    }

    private String resolveBundleName(Bundle bundle, Intent intent) {
        if (bundle != null) {
            Object value = bundle.get(EXTRA_BUNDLE_NAME);
            if (value != null) {
                return String.valueOf(value);
            }
        }
        return intent.getPackage();
    }

    private JSONObject readParameters(Bundle bundle) throws JSONException {
        JSONObject parameters = new JSONObject();
        if (bundle == null) {
            return parameters;
        }
        Object value = bundle.get(EXTRA_PARAMETERS);
        if (value == null) {
            return parameters;
        }
        if (!(value instanceof String)) {
            return parameters;
        }
        JSONObject jsonObject = new JSONObject(String.valueOf(value));
        Iterator<String> keys = jsonObject.keys();
        while (keys.hasNext()) {
            String name = keys.next();
            parameters.put(name, jsonObject.get(name));
        }
        return parameters;
    }

    private void appendBundleValues(Bundle bundle, JSONObject payload, JSONObject parameters) throws JSONException {
        if (bundle == null || bundle.size() == 0) {
            return;
        }
        for (String extraKey : bundle.keySet()) {
            Object value = bundle.get(extraKey);
            if (appendReservedExtra(extraKey, value, payload)) {
                continue;
            }
            if (EXTRA_PARAMETERS.equals(extraKey) || parameters.has(extraKey)) {
                continue;
            }
            putJsonValue(parameters, extraKey, value);
        }
    }

    private boolean appendReservedExtra(String extraKey, Object value, JSONObject payload) throws JSONException {
        if (EXTRA_BUNDLE_NAME.equals(extraKey)) {
            payload.put(JSON_BUNDLE_NAME, value == null ? "" : String.valueOf(value));
            return true;
        }
        if (EXTRA_CODE.equals(extraKey)) {
            payload.put(JSON_CODE, value instanceof Number ? ((Number) value).intValue() : 0);
            return true;
        }
        if (EXTRA_DATA.equals(extraKey)) {
            payload.put(JSON_DATA, value == null ? "" : String.valueOf(value));
            return true;
        }
        return false;
    }

    private String finalizePayload(JSONObject payload, JSONObject parameters) throws JSONException {
        if (!payload.has(JSON_CODE)) {
            payload.put(JSON_CODE, 0);
        }
        if (!payload.has(JSON_DATA)) {
            payload.put(JSON_DATA, "");
        }
        payload.put(JSON_PARAMETERS, parameters);
        return payload.toString();
    }

    private void putJsonValue(JSONObject jsonObject, String key, Object value) throws JSONException {
        if (value == null) {
            jsonObject.put(key, JSONObject.NULL);
            return;
        }
        Object wrappedValue = JSONObject.wrap(value);
        jsonObject.put(key, wrappedValue == null ? String.valueOf(value) : wrappedValue);
    }

    /**
     * nativeDestroy is used to destroy the native environment
     *
     * @param key the key of broadcast
     * @return boolean whether the broadcast is unregistered successfully
     */
    public synchronized boolean unRegisterBroadcast(String key) {
        if (context == null || TextUtils.isEmpty(key)) return false;
        BroadcastReceiver receiver = broadcastReceiverMap.remove(key);
        if (receiver == null) return true;
        try {
            context.unregisterReceiver(receiver);
            return true;
        } catch (IllegalArgumentException e) {
            ALog.w(LOG_TAG, "Receiver was already unregistered: " + key);
            return true;
        }
    }

    /**
     * nativeInit is used to initialize the native environment
     */
    protected native void nativeInit();

    /**
     * nativeReceiveBroadcast is used to receive broadcast
     *
     * @param key the key of broadcast
     * @param action the action of broadcast
     * @param json the json of broadcast
     */
    protected native void nativeReceiveBroadcast(String key, String action, String json);
}
