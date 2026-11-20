package br.com.rian.deck;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;

final class KioskPolicy {
    static final String ARMED = "kiosk-armed";
    private final Context context;
    private final DevicePolicyManager dpm;
    private final ComponentName admin, home;

    KioskPolicy(Context context) {
        this.context = context;
        dpm = context.getSystemService(DevicePolicyManager.class);
        admin = new ComponentName(context, DeckAdminReceiver.class);
        home = new ComponentName(context.getPackageName(), context.getPackageName() + ".KioskHome");
    }

    boolean owner() { return dpm != null && dpm.isDeviceOwnerApp(context.getPackageName()); }

    boolean locked() {
        return context.getSystemService(ActivityManager.class).getLockTaskModeState()
            == ActivityManager.LOCK_TASK_MODE_LOCKED;
    }

    void enter(Activity activity) {
        if (!owner()) throw new IllegalStateException("Device Owner necessário");
        if (locked()) return;
        dpm.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE);
        dpm.setLockTaskPackages(admin, new String[]{context.getPackageName()});
        if (!dpm.isLockTaskPermitted(context.getPackageName()))
            throw new IllegalStateException("App não autorizado para Lock Task");
        context.getPackageManager().setComponentEnabledSetting(home, PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP);
        IntentFilter launcher = new IntentFilter(Intent.ACTION_MAIN);
        launcher.addCategory(Intent.CATEGORY_HOME);
        launcher.addCategory(Intent.CATEGORY_DEFAULT);
        dpm.addPersistentPreferredActivity(admin, launcher, home);
        dpm.setStatusBarDisabled(admin, true);
        activity.startLockTask();
        if (!locked()) throw new IllegalStateException("Lock Task não confirmado");
    }

    void release(Activity activity) {
        if (!owner()) throw new IllegalStateException("Device Owner necessário");
        dpm.setStatusBarDisabled(admin, false);
        dpm.clearPackagePersistentPreferredActivities(admin, context.getPackageName());
        context.getPackageManager().setComponentEnabledSetting(home, PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP);
        if (locked()) {
            try { activity.stopLockTask(); }
            catch (IllegalStateException notStarter) { dpm.setLockTaskPackages(admin, new String[0]); }
        }
        if (locked()) throw new IllegalStateException("Lock Task permanece ativo");
        dpm.setLockTaskPackages(admin, new String[0]);
    }
}
