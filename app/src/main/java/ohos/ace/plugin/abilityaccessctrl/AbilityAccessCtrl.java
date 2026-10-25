/*
 * Copyright (c) 2023-2024 Huawei Device Co., Ltd.
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

package ohos.ace.plugin.abilityaccessctrl;

import java.util.List;
import java.util.Map;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

import ohos.ace.adapter.ALog;

/**
 * AbilityAccessCtrl
 *
 * @since 1
 */
public class AbilityAccessCtrl {
    private static final String LOG_TAG = "AbilityAccessCtrl";

    private Context mContext;

    /**
     * AbilityAccessCtrl
     *
     * @param context context of the application
     */
    public AbilityAccessCtrl(Context context) {
        mContext = context;
        nativeInit();
    }

    /**
     * checkPermission
     *
     * @param permission The permission name to be verified.
     * @return boolean
     */
    public boolean checkPermission(String permission) {
        if (mContext == null || permission == null || permission.isEmpty()) return false;
        ALog.i(LOG_TAG, "AbilityAccessCtrl: check from java");
        PackageManager pm = mContext.getPackageManager();
        if (pm.checkPermission(permission, mContext.getPackageName()) == PackageManager.PERMISSION_GRANTED) {
            ALog.i(LOG_TAG, "AbilityAccessCtrl: has permission");
            return true;
        } else {
            ALog.i(LOG_TAG, "AbilityAccessCtrl: not has permission");
            return false;
        }
    }

    /**
     * requestPermissions
     *
     * @param permissions The permission list to be requested.
     */
    public void requestPermissions(String[] permissions) {
        if (permissions == null || permissions.length == 0) return;
        ALog.i(LOG_TAG, "AbilityAccessCtrl: request from java");
        ALog.i(LOG_TAG, "AbilityAccessCtrl: request  " + permissions.length);
        Activity curActivity = getActivity();
        if (curActivity == null) {
            return;
        }
        curActivity.runOnUiThread(() -> {
            if (curActivity.isFinishing() || curActivity.isDestroyed()) return;
            Fragment fragment = getFragment(curActivity);
            if (fragment != null) fragment.requestPermissions(permissions, 1);
            else curActivity.requestPermissions(permissions, 1);
        });
    }

    private Activity getActivity() {
        return com.kira.harmoid.bridge.ActivityTracker.getActivity(mContext);
    }

    private Fragment getFragment(Activity curActivity) {
        try {
            if (!(curActivity instanceof FragmentActivity)) {
                return null;
            }
        } catch (NoClassDefFoundError exception) {
            ALog.w(LOG_TAG, "FragmentActivity no class define found");
            return null;
        }
        FragmentActivity fragmentActivity = (FragmentActivity) curActivity;
        FragmentManager fragmentManager = fragmentActivity.getSupportFragmentManager();
        if (fragmentManager == null) {
            return null;
        }
        List<Fragment> fragments = fragmentManager.getFragments();
        if (fragments == null) {
            return null;
        }
        for (Fragment fragment : fragments) {
            if (fragment == null) {
                continue;
            }
            if (fragment.getUserVisibleHint()) {
                return fragment;
            }
        }
        return null;
    }

    /**
     * nativeInit
     * Register the initialization method of the plugin for the plugin construction to call.
     */
    protected native void nativeInit();
}
