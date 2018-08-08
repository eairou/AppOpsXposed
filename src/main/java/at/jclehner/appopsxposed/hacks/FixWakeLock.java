/*
 * AppOpsXposed - AppOps for Android 4.3+
 * Copyright (C) 2013, 2014 Joseph C. Lehner
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of  MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package at.jclehner.appopsxposed.hacks;

import android.annotation.TargetApi;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;

import java.util.Set;

import at.jclehner.appopsxposed.Hack;
import at.jclehner.appopsxposed.util.AppOpsManagerWrapper;
import at.jclehner.appopsxposed.util.ExceptionEater;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodHook.Unhook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;


@TargetApi(19)
public class FixWakeLock extends Hack {
    private static final boolean ENABLE_PER_TAG_FILTERING = false;
    private static final boolean DEBUG = false;

    private static final String POWER_SERVICE = "com.android.server.power.PowerManagerService";

    public FixWakeLock(SharedPreferences prefs) {
        super(prefs);
    }

    @Override
    protected void handleLoadFrameworkPackage(LoadPackageParam lpparam) throws Throwable {
        if (AppOpsManagerWrapper.OP_WAKE_LOCK == -1) {
            log("No OP_WAKE_LOCK; bailing out!");
            return;
        }

        hookMethods();
    }

    @SuppressWarnings("unchecked")
    @Override
    protected void handleLoadAnyPackage(LoadPackageParam lpparam) throws Throwable {
        if (AppOpsManagerWrapper.OP_WAKE_LOCK == -1)
            return;

        // On some (HTC) ROMs, an IllegalArgumentException thrown by the PowerManagerService
        // (because the lock is not actually held) is not ignored in setWorkSource().

        Class<?> clazz = loadClass("android.os.PowerManager$WakeLock");
        XposedBridge.hookAllMethods(clazz, "setWorkSource", new ExceptionEater(IllegalArgumentException.class));
    }

    @Override
    protected String onGetKeySuffix() {
        return "wake_lock";
    }

    private static final String ACQUIRE_WAKE_LOCK_CLASS =
            POWER_SERVICE +
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP ?
                            "$BinderService" : "");

    private void hookMethods() throws Throwable {
        Class<?> pwrMgrSvcClazz = loadClass(ACQUIRE_WAKE_LOCK_CLASS);

        Set<Unhook> unhooks = XposedBridge.hookAllMethods(pwrMgrSvcClazz,
                "acquireWakeLock", mAcquireHook);
        log("Hooked " + unhooks.size() + " functions");
    }

    private boolean canAcquire(String packageName, String tag) {
        if (ENABLE_PER_TAG_FILTERING) {
            String blacklistKey = "wakelock_hack_is_blacklist/" + packageName;
            if (!mPrefs.contains(blacklistKey))
                return false;

            boolean isBlacklist = mPrefs.getBoolean(blacklistKey, true);
            Set<String> tags = mPrefs.getStringSet("wakelock_hack_tags/" + packageName, null);
            if (tags == null || tags.isEmpty() || !tags.contains(tag))
                return isBlacklist;

            return !isBlacklist;
        }

        return false;
    }

    private static Context getContextFromThis(Object object) {
        Object location = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP ?
                XposedHelpers.getSurroundingThis(object) : object;

        return (Context) XposedHelpers.getObjectField(location, "mContext");
    }

    private final XC_MethodHook mAcquireHook = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            IBinder lock = (IBinder) param.args[0];
            String tag = (String) param.args[2];
            String packageName = param.args[3] instanceof String ?
                    (String) param.args[3] : null;

            int uid = Binder.getCallingUid();

            // Since we want this hack to replicate the expected behaviour,
            // we have to do some sanity checks first. On error we return
            // and let the hooked function throw an exception.

            if (lock == null || packageName == null)
                return;

            Context ctx = getContextFromThis(param.thisObject);
            if (ctx == null)
                return;

            ctx.enforceCallingOrSelfPermission(
                    android.Manifest.permission.WAKE_LOCK, null);

            AppOpsManagerWrapper appOps = AppOpsManagerWrapper.from(ctx);
            if (appOps.checkOpNoThrow(AppOpsManagerWrapper.OP_WAKE_LOCK, uid, packageName) != AppOpsManager.MODE_ALLOWED) {
                if (tag != null && canAcquire(packageName, tag)) {
                    if (DEBUG) {
                        log("Allowing acquisition of WakeLock '" + tag +
                                "' for app " + packageName);
                    }
                    return;
                }

                if (DEBUG) {
                    log("Prevented acquisition of WakeLock '" + tag +
                            "' for app " + packageName);
                }

                param.setResult(null);
            }
        }
    };
}

