/*
 * AppOpsXposed - AppOps for Android 4.3+
 * Copyright (C) 2013-2015 Joseph C. Lehner
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

package at.jclehner.appopsxposed;

import android.app.AndroidAppHelper;
import android.content.Intent;
import android.content.res.XModuleResources;

import at.jclehner.appopsxposed.util.Constants;
import at.jclehner.appopsxposed.util.Res;
import at.jclehner.appopsxposed.util.Util;
import de.robv.android.xposed.IXposedHookInitPackageResources;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_InitPackageResources.InitPackageResourcesParam;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import xeed.library.xposed.BaseModule;

public class AppOpsXposed extends BaseModule implements IXposedHookInitPackageResources {
    public static final String MODULE_PACKAGE = "at.jclehner.appopsxposed.re";
    public static final String SETTINGS_PACKAGE = "com.android.settings";
    public static final String SETTINGS_MAIN_ACTIVITY = SETTINGS_PACKAGE + ".Settings";
    public static final String APP_OPS_FRAGMENT = "com.android.settings.applications.AppOpsSummary";
    static final String APP_OPS_DETAILS_FRAGMENT = "com.android.settings.applications.AppOpsDetails";

    static {
        Util.logger = new Util.Logger() {

            @Override
            public void log(Throwable t) {
                XposedBridge.log(t);
            }

            @Override
            public void log(String s) {
                XposedBridge.log(s);
            }
        };
    }

    @Override
    protected final String getLogTag() {
        return "AOX";
    }

    @Override
    protected final long getVersion() {
        return 13005;
    }

    @Override
    protected final void reloadPrefs(Intent in) {
    }

    @Override
    protected final String getModulePackage() {
        return MODULE_PACKAGE;
    }

    @Override
    public void initZygote(StartupParam param) {
        super.initZygote(param);
        Res.modRes = XModuleResources.createInstance(param.modulePath, null);
        ApkVariant.initVariants(mPrefs);
        Hack.initHacks(mPrefs);
    }

    @Override
    public void handleInitPackageResources(InitPackageResourcesParam resparam) {
        if (!ApkVariant.isSettingsPackage(resparam.packageName))
            return;

        for (int i = 0; i != Res.icons.length; ++i)
            Res.icons[i] = resparam.res.addResource(Res.modRes, Constants.ICONS[i]);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) throws Throwable {
        super.handleLoadPackage(param);

        boolean isSettings = ApkVariant.isSettingsPackage(param.packageName);

        for (Hack hack : Hack.getAllEnabled(true)) {
            try {
                hack.handleLoadPackage(param);
            } catch (Throwable t) {
                log(hack.getClass().getSimpleName() + ": [!!]");
                Util.debug(t);
            }
        }

        if (!isSettings)
            return;

        Class<?> instrumentation = XposedHelpers.findClass("android.app.Instrumentation", param.classLoader);
        XposedBridge.hookAllMethods(instrumentation, "newActivity", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (Res.settingsRes == null) {
                    Res.settingsRes = AndroidAppHelper.currentApplication().getResources();
                }
            }
        });

        String forceVariant = mPrefs.getString("force_variant", "");
        for (ApkVariant variant : ApkVariant.getAllMatching(param, forceVariant)) {
            String variantName = "  " + variant.getClass().getSimpleName();

            try {
                variant.handleLoadPackage(param);
                log(variantName + ": [OK]");
                break;
            } catch (Throwable t) {
                Util.debug(variantName + ": [!!]");
                Util.debug(t);
            }
        }
    }
}
