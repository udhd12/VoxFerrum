package dev.voxferrum;

import android.content.*;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

public class ReplayTile extends TileService {
    private boolean listening;
    private final BroadcastReceiver changes = new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent i){render();}
    };
    private void render(){
        Tile tile=getQsTile();
        if(tile==null) return;
        boolean active=ReplayService.running;
        tile.setState(active ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(active ? "Сохранить повтор" : "Аудиоповтор");
        tile.setSubtitle(active ? ReplayService.saving ? "Сохранение…" : ReplayWidget.duration(this) : "Включить");
        tile.updateTile();
    }
    @Override public void onStartListening(){
        super.onStartListening();
        if(!listening){
            IntentFilter filter=new IntentFilter(ReplayService.CHANGED);
            if(Build.VERSION.SDK_INT>=33) registerReceiver(changes,filter,Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(changes,filter);
            listening=true;
        }
        render();
    }
    @Override public void onStopListening(){
        if(listening){unregisterReceiver(changes); listening=false;}
        super.onStopListening();
    }
    @Override public void onClick(){
        super.onClick();
        if(ReplayService.running) ReplayService.save(this);
        else if(Build.VERSION.SDK_INT>=34) startActivityAndCollapse(ReplayWidget.activity(this,ReplayWidget.ARM));
        else startActivityAndCollapse(ReplayWidget.open(this,ReplayWidget.ARM));
    }
}
