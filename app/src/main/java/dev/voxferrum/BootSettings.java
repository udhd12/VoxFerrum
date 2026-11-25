package dev.voxferrum;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import android.widget.*;

final class BootSettings extends LinearLayout {
    static final int PERMISSION_REQUEST=81;
    private final MainActivity screen;
    private final Switch toggle;
    private final TextView detail;
    private final Button notifications;

    BootSettings(MainActivity activity){
        super(activity);
        screen=activity;
        setOrientation(VERTICAL);
        BootReminder.channel(screen);
        toggle=new Switch(screen);
        toggle.setText("Запуск после включения телефона");
        toggle.setTextColor(screen.skin.ink);
        toggle.setTextSize(17);
        toggle.setMinHeight(screen.dp(48));
        toggle.setChecked(BootReminder.enabled(screen));
        addView(toggle);
        detail=screen.text("",14,screen.skin.muted,null);
        addView(detail);
        notifications=screen.button("Разрешить уведомления",false,v->fixNotifications());
        addView(notifications);
        toggle.setOnCheckedChangeListener((button,on)->{
            BootReminder.setEnabled(screen,on);
            refresh();
            if(on && !BootReminder.canNotify(screen)) fixNotifications();
        });
        refresh();
    }
    void refresh(){
        boolean enabled=BootReminder.enabled(screen);
        boolean allowed=enabled && BootReminder.canNotify(screen);
        detail.setText(!enabled ? "После перезагрузки повтор запускается вручную." :
                allowed ? "После первой разблокировки нажмите «Включить повтор» в уведомлении. Для звука приложений Android попросит подтвердить захват."
                        : "Уведомления отключены. При первом открытии Vox Ferrum после загрузки запустится выбранный режим.");
        notifications.setVisibility(enabled && !allowed ? View.VISIBLE : View.GONE);
    }
    private void fixNotifications(){
        if(Build.VERSION.SDK_INT>=33 && screen.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED
                && (!screen.prefs().getBoolean("askedBootNotification",false)
                     || screen.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))){
            screen.prefs().edit().putBoolean("askedBootNotification",true).apply();
            screen.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},PERMISSION_REQUEST);
            return;
        }
        NotificationManager manager=screen.getSystemService(NotificationManager.class);
        Intent intent=new Intent(manager.areNotificationsEnabled()?Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS:Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE,screen.getPackageName())
                .putExtra(Settings.EXTRA_CHANNEL_ID,BootReminder.CHANNEL);
        screen.startActivity(intent);
    }
    @Override public void onWindowFocusChanged(boolean focus){
        super.onWindowFocusChanged(focus);
        if(focus && detail!=null) refresh();
    }
}
