package dev.voxferrum;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.*;
import android.view.View;
import android.widget.RemoteViews;

public class ReplayWidget extends AppWidgetProvider {
    static final String ARM = "dev.voxferrum.ARM", ARCHIVE = "dev.voxferrum.ARCHIVE";
    static Intent open(Context c,String action) {
        return new Intent(c, MainActivity.class).setAction(action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    }
    static PendingIntent activity(Context c,String action) {
        return PendingIntent.getActivity(c,0,open(c,action),PendingIntent.FLAG_IMMUTABLE);
    }
    private static PendingIntent service(Context c,String action) {
        return PendingIntent.getService(c,0,new Intent(c,ReplayService.class).setAction(action),PendingIntent.FLAG_IMMUTABLE);
    }
    static void refresh(Context c) {
        AppWidgetManager mgr=AppWidgetManager.getInstance(c);
        int[] ids=mgr.getAppWidgetIds(new ComponentName(c,ReplayWidget.class));
        if(ids.length>0) render(c,mgr,ids);
    }
    static String duration(Context c) {
        int sec=c.getSharedPreferences("vox",0).getInt("seconds",30);
        return sec%60==0 ? sec/60+Lang.text(R.string.min) : sec+Lang.text(R.string.s);
    }
    private static void render(Context c, AppWidgetManager mgr,int[] ids) {
        boolean active=ReplayService.running;
        RemoteViews v=new RemoteViews(c.getPackageName(),R.layout.replay_widget);
        String state = !active ? Lang.text(R.string.capture_off) : ReplayService.silent ? Lang.text(R.string.buffer_is_silent) :
                Lang.text(R.string.live_alt)+duration(c);
        long now=android.os.SystemClock.elapsedRealtime();
        long recent=Math.max(ReplayService.markedAt, ReplayService.savedAt);
        if(active && recent>0 && now-recent<2200)
            state=recent==ReplayService.markedAt ? Lang.text(R.string.marker_added) : Lang.text(R.string.replay_saved);
        v.setTextViewText(R.id.widget_archive,Lang.text(R.string.widget_archive_link));
        v.setTextViewText(R.id.widget_mark,Lang.text(R.string.mark));
        v.setTextViewText(R.id.widget_stop,Lang.text(R.string.stop));
        v.setTextViewText(R.id.widget_state,state);
        v.setTextViewText(R.id.widget_save,active ? ReplayService.saving?Lang.text(R.string.saving):Lang.text(R.string.save_alt) : Lang.text(R.string.enable_replay));
        v.setOnClickPendingIntent(R.id.widget_save,active ? service(c,ReplayService.SAVE) : activity(c,ARM));
        v.setOnClickPendingIntent(R.id.widget_archive,activity(c,ARCHIVE));
        v.setOnClickPendingIntent(R.id.widget_mark,service(c,ReplayService.MARK));
        v.setOnClickPendingIntent(R.id.widget_stop,service(c,ReplayService.STOP));
        v.setViewVisibility(R.id.widget_mark,active?View.VISIBLE:View.GONE);
        v.setViewVisibility(R.id.widget_stop,active?View.VISIBLE:View.GONE);
        mgr.updateAppWidget(ids,v);
    }
    @Override public void onUpdate(Context c,AppWidgetManager m,int[] ids){render(c,m,ids);}
    @Override public void onReceive(Context c,Intent intent){
        super.onReceive(c,intent);
        if(Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) refresh(c);
    }
}
