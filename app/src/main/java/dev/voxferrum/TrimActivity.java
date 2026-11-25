package dev.voxferrum;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.media.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.*;

public class TrimActivity extends Activity {
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private Skin skin;
    private Clips.Clip source,cut;
    private int from,to,cutStart=-1,cutEnd=-1;
    private TextView startLabel,endLabel,readout;
    private SeekBar startSlider,endSlider;
    private Button listen,send;
    private ClipWave wave;
    private MediaPlayer player;
    private boolean working,visible;
    private LinearLayout page;
    private List<Integer> marks;

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        String name=getIntent().getStringExtra("name");
        if(getIntent().getData()==null || name==null){finish();return;}
        source=new Clips.Clip(getIntent().getData(),name,0,getIntent().getLongExtra("bytes",0));
        if(source.durationMs<1){finish();return;}
        from=state==null?0:state.getInt("start",0); to=state==null?source.durationMs:state.getInt("end",source.durationMs);
        marks=ClipNotes.marks(this,source.uri);
        skin=new Skin(getSharedPreferences("vox",0).getBoolean("tide",false));
        if(skin.tide) getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        ScrollView scroll=new ScrollView(this); scroll.setBackgroundColor(skin.page);
        scroll.setOnApplyWindowInsetsListener((v,i)->{
            v.setPadding(i.getSystemWindowInsetLeft(),i.getSystemWindowInsetTop(),i.getSystemWindowInsetRight(),i.getSystemWindowInsetBottom());return i;
        });
        page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(18),dp(12),dp(18),dp(18));
        scroll.addView(page);setContentView(scroll);
        page.addView(button(Lang.text(R.string.archive_alt),false,v->finish()));
        TextView title=label(Lang.text(R.string.trim_replay),27); title.setTypeface(skin.display); page.addView(title);
        readout=label("",15);page.addView(readout);
        wave=new ClipWave(this,skin,source.durationMs,marks,this::boundary);
        page.addView(wave,-1,dp(110));
        if(!marks.isEmpty()){
            page.addView(button(Lang.text(R.string.go_to_marker)+marks.size(),false,v->chooseMark()));
        }
        startLabel=label("",17);page.addView(startLabel);startSlider=slider(true);
        endLabel=label("",17);page.addView(endLabel);endSlider=slider(false);
        listen=button(Lang.text(R.string.play_selection),false,v->{if(player!=null) stop();else preview();});page.addView(listen);
        send=button(Lang.text(R.string.send_selection),true,v->chooseDestination());page.addView(send);
        page.addView(label(Lang.text(R.string.the_trimmed_section_is_saved_as_a),14));
        update();
        io.execute(()->{
            try{
                float[] peaks=ClipWave.read(getApplicationContext(),source.uri);
                main.post(()->{if(!isDestroyed()) wave.setPeaks(peaks);});
            }catch(IOException|RuntimeException e){
                main.post(()->{if(!isDestroyed()) toast(Lang.text(R.string.could_not_read_waveform));});
            }
        });
    }
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView label(String text,int sp){
        TextView t=new TextView(this);t.setText(text);t.setTextSize(sp);t.setTextColor(skin.ink);t.setPadding(0,dp(8),0,dp(6));return t;
    }
    private Button button(String s,boolean primary,android.view.View.OnClickListener action){
        Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(dp(48));
        b.setTextColor(primary?skin.actionInk:skin.ink);b.setBackground(skin.button(primary?skin.action:skin.panel,skin.line,dp(skin.tide?12:3),dp(1)));
        b.setOnClickListener(action);return b;
    }
    private SeekBar slider(boolean first){
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
        Button minus=button("−",false,v->boundary(first,(first?from:to)-100));
        Button plus=button("+",false,v->boundary(first,(first?from:to)+100));
        String which=first?Lang.text(R.string.start):Lang.text(R.string.end);
        minus.setContentDescription(which+Lang.text(R.string.earlier_by_0_1_seconds)); plus.setContentDescription(which+Lang.text(R.string.later_by_0_1_seconds));
        SeekBar bar=new SeekBar(this);bar.setMax(source.durationMs);bar.setContentDescription(which+Lang.text(R.string.of_selection));
        bar.setThumbTintList(ColorStateList.valueOf(skin.accent));bar.setProgressTintList(ColorStateList.valueOf(skin.accent));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar b){}
            public void onStopTrackingTouch(SeekBar b){}
            public void onProgressChanged(SeekBar b,int ms,boolean user){if(user)boundary(first,ms);}
        });
        row.addView(minus,dp(48),dp(48));row.addView(bar,new LinearLayout.LayoutParams(0,dp(48),1));row.addView(plus,dp(48),dp(48));
        page.addView(row);return bar;
    }
    private static String time(int ms){
        return String.format(Locale.ROOT,"%d:%02d.%d",ms/60000,ms/1000%60,ms/100%10);
    }
    private void chooseMark(){
        String[] options=new String[marks.size()];
        for(int i=0;i<options.length;i++) options[i]=Lang.text(R.string.marker)+(i+1)+"   ·   "+time(marks.get(i));
        new VoxMenu(this).setTitle(Lang.text(R.string.choose_a_moment)).setItems(options,(d,i)->{
            if(working)return;
            stop();int at=marks.get(i);from=Math.max(0,at-5000);to=Math.min(source.durationMs,at+5000);update();
        }).show();
    }
    private void boundary(boolean first,int ms){
        if(working){update();return;}
        stop();int gap=Math.min(100,source.durationMs);
        if(first) from=Math.max(0,Math.min(ms,to-gap));else to=Math.min(source.durationMs,Math.max(ms,from+gap));
        update();
    }
    private void update(){
        startLabel.setText(Lang.text(R.string.start_alt)+time(from));endLabel.setText(Lang.text(R.string.end_alt)+time(to));
        startSlider.setProgress(from);endSlider.setProgress(to);wave.range(from,to);
        readout.setText(Lang.text(R.string.selected_alt)+time(to-from)+Lang.text(R.string.of)+time(source.durationMs));
    }
    private void preview(){
        stop();MediaPlayer p=new MediaPlayer();player=p;listen.setText(Lang.text(R.string.stop));
        try {
            p.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE).build());
            p.setDataSource(this,source.uri);
            p.setOnErrorListener((mp,w,e)->{if(player==mp){stop();toast(Lang.text(R.string.could_not_play_recording));}return true;});
            p.setOnCompletionListener(mp->{if(player==mp)stop();});
            p.setOnPreparedListener(mp->{
                if(player!=mp)return;
                if(from==0)startPreview(mp);else mp.seekTo((long)from,MediaPlayer.SEEK_CLOSEST);
            });
            p.setOnSeekCompleteListener(mp->{if(player==mp)startPreview(mp);});
            p.prepareAsync();
        }catch(IOException|RuntimeException e){stop();toast(Lang.text(R.string.file_unavailable));}
    }
    private void startPreview(MediaPlayer p){
        p.start();main.post(tick);
    }
    private final Runnable tick=new Runnable(){ public void run(){
        if(player==null)return;
        int ms=player.getCurrentPosition();
        if(ms>=to){stop();return;} wave.cursor(ms);main.postDelayed(this,Math.max(1,Math.min(40,to-ms)));
    }};
    private void stop(){
        main.removeCallbacks(tick);
        if(player!=null){player.release();player=null;}
        if(wave!=null)wave.cursor(-1);
        if(listen!=null)listen.setText(Lang.text(R.string.play_selection));
    }
    private void chooseDestination(){
        new VoxMenu(this).setTitle(Lang.text(R.string.where_should_the_selection_go))
            .setItems(new String[]{Lang.text(R.string.telegram_bot_alt),Lang.text(R.string.share_via)},(d,i)->export(i==0)).show();
    }
    private void export(boolean bot){
        if(working)return;
        stop();
        if(from==0 && to==source.durationMs){deliver(source,bot);return;}
        if(cut!=null && cutStart==from && cutEnd==to){deliver(cut,bot);return;}
        final int a=from,b=to;
        working=true;send.setEnabled(false);listen.setEnabled(false);send.setText(Lang.text(R.string.preparing_wav));
        io.execute(()->{
            Clips.Clip result=null;
            try{result=Clips.extract(getApplicationContext(),source,a,b);}catch(IOException|RuntimeException ignored){}
            final Clips.Clip ready=result;
            main.post(()->{
                if(isDestroyed()||isFinishing())return;
                working=false;send.setText(Lang.text(R.string.send_selection));send.setEnabled(true);listen.setEnabled(true);
                if(ready==null){toast(Lang.text(R.string.could_not_write_selection_check_free_storage));return;}
                cut=ready;cutStart=a;cutEnd=b;
                if(visible)deliver(ready,bot);else toast(Lang.text(R.string.selection_saved_in_archive));
            });
        });
    }
    private void deliver(Clips.Clip clip,boolean bot){
        if(bot){Telegram.send(this,clip.uri,clip.name,clip.bytes);return;}
        Intent i=new Intent(Intent.ACTION_SEND).setType("audio/wav").putExtra(Intent.EXTRA_STREAM,clip.uri)
             .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        i.setClipData(ClipData.newUri(getContentResolver(),clip.name,clip.uri));
        startActivity(Intent.createChooser(i,Lang.text(R.string.send_selection_alt)));
    }
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    @Override protected void onStart(){super.onStart();visible=true;}
    @Override protected void onStop(){visible=false;stop();super.onStop();}
    @Override protected void onDestroy(){io.shutdownNow();super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putInt("start",from);out.putInt("end",to);}
}
