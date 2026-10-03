package com.rbabbit.alarm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restores enabled alarms after Android clears AlarmManager entries during reboot. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) AlarmScheduler.syncAll(context);
    }
}
