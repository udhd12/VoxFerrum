package dev.voxferrum;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;

/** Resumes only from an unlocked, user-visible activity. Consent tokens never survive a reboot. */
public final class BootReminder extends BroadcastReceiver {
    static final String RESUME = "dev.voxferrum.RESUME_AFTER_BOOT";
    private static final String DISMISS = "dev.voxferrum.DISMISS_BOOT";
    static final String CHANNEL = "vox_after_boot";
    private static final int ID = 73;

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("vox",0); }
    static boolean enabled(Context c) { return prefs(c).getBoolean("bootEnabled", true); }

    static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("bootEnabled",on).apply();
        if (!on) clear(c);
    }
    static void clear(Context c) {
        if (prefs(c).contains("bootPending")) prefs(c).edit().remove("bootPending").apply();
        c.getSystemService(NotificationManager.class).cancel(ID);
    }
    static boolean consume(Context c) {
        if (!enabled(c) || !prefs(c).getBoolean("bootPending",false)) return false;
        clear(c);
        return true;
    }
    static NotificationChannel channel(Context c) {
        NotificationManager manager=c.getSystemService(NotificationManager.class);
        NotificationChannel channel=manager.getNotificationChannel(CHANNEL);
        if (channel==null) {
            channel=new NotificationChannel(CHANNEL,"После включения телефона",NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Предложение включить аудиоповтор после разблокировки");
            manager.createNotificationChannel(channel);
        }
        return channel;
    }
    static boolean canNotify(Context c) {
        NotificationManager manager=c.getSystemService(NotificationManager.class);
        return (Build.VERSION.SDK_INT < 33 || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)
            && manager.areNotificationsEnabled() && channel(c).getImportance()!=NotificationManager.IMPORTANCE_NONE;
    }
    @Override public void onReceive(Context c, Intent intent) {
        if (DISMISS.equals(intent.getAction())) { clear(c); return; }
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) || !enabled(c) || ReplayService.running) return;
        int boot=Settings.Global.getInt(c.getContentResolver(),Settings.Global.BOOT_COUNT,-1);
        if (boot>=0 && prefs(c).getInt("bootHandled",-2)==boot) return;
        prefs(c).edit().putInt("bootHandled",boot).putBoolean("bootPending",true).apply();
        if (!canNotify(c)) return;
        PendingIntent start=ReplayWidget.activity(c,RESUME);
        PendingIntent dismiss=PendingIntent.getBroadcast(c,ID,new Intent(c,BootReminder.class)
                .setAction(DISMISS),PendingIntent.FLAG_IMMUTABLE);
        String source=prefs(c).getString("audioSource","playback");
        String message="mic".equals(source) ? "Телефон включён. Нажмите, чтобы запустить буфер микрофона."
                : "Телефон включён. Нажмите и подтвердите захват звука приложений.";
        Notification note=new Notification.Builder(c,CHANNEL)
                .setSmallIcon(R.drawable.ic_wave).setContentTitle("Включить аудиоповтор?")
                .setContentText(message).setStyle(new Notification.BigTextStyle().bigText(message))
                .setContentIntent(start).setDeleteIntent(dismiss).setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null,"Включить повтор",start).build()).build();
        c.getSystemService(NotificationManager.class).notify(ID,note);
    }
}
