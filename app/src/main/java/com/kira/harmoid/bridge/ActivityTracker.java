package com.kira.harmoid.bridge;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Bundle;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Process-local foreground Activity, without hidden ActivityThread reflection. */
public final class ActivityTracker implements Application.ActivityLifecycleCallbacks {
    private static volatile WeakReference<Activity> resumed = new WeakReference<>(null);
    private static WeakReference<Application> owner = new WeakReference<>(null);

    public static synchronized void initialize(Application application) {
        if (owner.get() != application) {
            application.registerActivityLifecycleCallbacks(new ActivityTracker());
            owner = new WeakReference<>(application);
            resumed = new WeakReference<>(null);
        }
    }

    public static Activity getActivity(Context context) {
        Set<Context> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        while (context != null && visited.add(context)) {
            if (context instanceof Activity) {
                Activity activity = (Activity) context;
                if (isUsable(activity)) return activity;
                break;
            }
            if (!(context instanceof ContextWrapper)) break;
            context = ((ContextWrapper) context).getBaseContext();
        }
        Activity activity = resumed.get();
        return isUsable(activity) ? activity : null;
    }

    private static boolean isUsable(Activity activity) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }

    @Override public void onActivityResumed(Activity activity) { resumed = new WeakReference<>(activity); }
    @Override public void onActivityPaused(Activity activity) { clear(activity); }
    @Override public void onActivityDestroyed(Activity activity) { clear(activity); }
    private static void clear(Activity activity) {
        if (resumed.get() == activity) resumed = new WeakReference<>(null);
    }
    @Override public void onActivityCreated(Activity activity, Bundle state) { }
    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
}
