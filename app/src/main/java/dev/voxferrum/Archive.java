package dev.voxferrum;

import android.content.ClipData;
import android.content.Intent;
import android.database.ContentObserver;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class Archive {
    private final MainActivity screen;
    private final Skin skin;
    private final TextView status, totals;
    private final Button filter;
    private boolean favoritesOnly, deleting;
    private final ListView list;
    private final Rows adapter;
    private List<Clips.Clip> clips = Collections.emptyList(), visibleClips = Collections.emptyList();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean closed;
    private MediaPlayer player;
    private Uri listening;
    private final SimpleDateFormat stamp = new SimpleDateFormat("d MMMM, HH:mm:ss", Lang.locale());
    private final Runnable reload = this::refresh;
    private final ContentObserver changes = new ContentObserver(main) {
        @Override public void onChange(boolean self) {
            main.removeCallbacks(reload); main.postDelayed(reload,350);
        }
    };

    Archive(MainActivity screen, LinearLayout box) {
        this.screen = screen; skin = screen.skin;
        box.addView(screen.text(Lang.text(R.string.archive),29,skin.ink,skin.display));
        screen.space(box,5);
        totals=screen.text("Music / VoxFerrum",13,skin.accent,skin.mono); box.addView(totals);
        screen.space(box,8);
        LinearLayout tools=screen.row();
        filter=screen.button(Lang.text(R.string.favorites),false,v -> {favoritesOnly=!favoritesOnly; display();});
        tools.addView(filter,screen.grow()); screen.gap(tools,6);
        tools.addView(screen.button(Lang.text(R.string.clean_up),false,v -> cleanup()),screen.grow()); box.addView(tools);
        screen.space(box,15);
        status = screen.text(Lang.text(R.string.loading_clips),16,skin.muted,null);
        box.addView(status);
        list = new ListView(screen); list.setDivider(null); list.setDividerHeight(screen.dp(10));
        list.setItemsCanFocus(true); list.setCacheColorHint(0);
        adapter = new Rows(); list.setAdapter(adapter);
        box.addView(list,new LinearLayout.LayoutParams(MainActivity.FULL,0,1));
        screen.getContentResolver().registerContentObserver(
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),true,changes);
    }
    void refresh() {
        if (closed) return;
        io.execute(() -> {
            try {
                List<Clips.Clip> rows = Clips.list(screen);
                main.post(() -> {
                    if (closed) return;
                    clips = rows; display();
                });
            } catch (RuntimeException error) {
                main.post(() -> {
                    if (!closed) { status.setText(Lang.text(R.string.could_not_read_the_archive_check_that));
                        status.setVisibility(View.VISIBLE); }
                });
            }
        });
    }

    private void display(){
        long bytes=0;
        java.util.ArrayList<Clips.Clip> shown=new java.util.ArrayList<>();
        for(Clips.Clip c:clips){
            bytes+=c.bytes;
            if(!favoritesOnly || ClipNotes.favorite(screen,c.uri)) shown.add(c);
        }
        visibleClips=shown;
        totals.setText(String.format(Lang.locale(),Lang.text(R.string.d_clips_1f_mb),clips.size(),bytes/1e6));
        filter.setSelected(favoritesOnly); filter.setText(favoritesOnly?Lang.text(R.string.show_all):Lang.text(R.string.favorites));
        status.setText(favoritesOnly?Lang.text(R.string.no_favorites_yet):Lang.text(R.string.no_clips_yet_start_replay_and_save));
        status.setVisibility(shown.isEmpty()?View.VISIBLE:View.GONE);
        adapter.notifyDataSetChanged();
    }

    private void cleanup(){
        if(deleting) return;
        String[] choices={Lang.text(R.string.older_than_7_days),Lang.text(R.string.older_than_30_days),Lang.text(R.string.older_than_90_days)};
        new VoxMenu(screen).setTitle(Lang.text(R.string.clean_up_archive))
            .setItems(choices,(d,i)->confirmCleanup(new int[]{7,30,90}[i]))
            .setNegativeButton(Lang.text(R.string.cancel),null).show();
    }
    private void confirmCleanup(int days){
        long cutoff=System.currentTimeMillis()-days*86400000L;
        java.util.ArrayList<Clips.Clip> candidates=new java.util.ArrayList<>();
        long bytes=0;
        for(Clips.Clip c:clips) if(c.dateMs<cutoff && !ClipNotes.favorite(screen,c.uri)){
            candidates.add(c); bytes+=c.bytes;
        }
        if(candidates.isEmpty()){
            Toast.makeText(screen,Lang.text(R.string.no_clips_outside_favorites_match_this_age),Toast.LENGTH_LONG).show(); return;
        }
        String detail=String.format(Lang.locale(),Lang.text(R.string.clips_older_than_d_days_d_1f),days,candidates.size(),bytes/1e6);
        new VoxMenu(screen).setTitle(Lang.text(R.string.delete_old_recordings))
            .setMessage(detail).setNegativeButton(Lang.text(R.string.cancel),null).setPositiveButton(Lang.text(R.string.delete),(d,w)->{
                deleting=true; endPlayback(); io.execute(()->{
                    int removed=0;
                    for(Clips.Clip c:candidates){
                        if(Thread.currentThread().isInterrupted()) break;
                        try {
                            if(!ClipNotes.favorite(screen,c.uri) && screen.getContentResolver().delete(c.uri,null,null)==1){
                                ClipNotes.remove(screen,c.uri); removed++;
                            }
                        }catch(RuntimeException ignored){ /* A removed or unavailable file can be skipped. */ }
                    }
                    final int count=removed;
                    main.post(()->{deleting=false;if(!closed){
                        Toast.makeText(screen,Lang.text(R.string.deleted)+count+Lang.text(R.string.of)+candidates.size(),Toast.LENGTH_LONG).show(); refresh();
                    }});
                });
            }).show();
    }
    private final class Rows extends BaseAdapter {
        @Override public int getCount() { return visibleClips.size(); }
        @Override public Clips.Clip getItem(int position) { return visibleClips.get(position); }
        @Override public long getItemId(int position) { return getItem(position).uri.hashCode(); }
        @Override public View getView(int position,View recycled,ViewGroup parent) {
            Clips.Clip clip = getItem(position);
            LinearLayout card = screen.plate();
            card.addView(screen.text(stamp.format(new Date(clip.dateMs)),20,skin.ink,skin.display));
            screen.space(card,5);
            Locale locale = Lang.locale();
            String duration = clip.durationMs >= 60000
                    ? String.format(locale, "%d:%02d", clip.durationMs / 60000, (clip.durationMs / 1000) % 60)
                    : String.format(locale, Lang.text(R.string.label_1f_s), clip.durationMs / 1000.0);
            String meta = String.format(locale, Lang.text(R.string.wav_s_1f_mb), duration, clip.bytes / 1000000.0);
            card.addView(screen.text(meta,13,skin.muted,skin.mono));
            screen.space(card,12);
            LinearLayout actions = screen.row();
            boolean selected = clip.uri.equals(listening);
            actions.addView(screen.button(selected ? Lang.text(R.string.stop) : Lang.text(R.string.play),false,v -> play(clip)),screen.grow());
            screen.gap(actions,6);
            actions.addView(screen.button("Telegram",false,v -> Telegram.send(screen,clip.uri,clip.name,clip.bytes)),screen.grow());
            screen.gap(actions,6);
            Button share = screen.button("↗",false,v -> share(clip)); share.setTextSize(20);
            share.setContentDescription(Lang.text(R.string.share_file) + clip.name);
            actions.addView(share,screen.dp(48),MainActivity.WRAP);
            card.addView(actions); screen.space(card,6);
            LinearLayout edit=screen.row();
            edit.addView(screen.button(Lang.text(R.string.trim),false,v -> {
                endPlayback();
                screen.startActivity(new Intent(screen,TrimActivity.class).setData(clip.uri)
                        .putExtra("name",clip.name).putExtra("bytes",clip.bytes));
            }),screen.grow());
            screen.gap(edit,6);
            boolean fav=ClipNotes.favorite(screen,clip.uri);
            Button seal=screen.button(fav?Lang.text(R.string.favorited):Lang.text(R.string.favorite),fav,v -> {
                if(deleting) return;
                ClipNotes.favorite(screen,clip.uri,!fav); display();
            });
            seal.setSelected(fav); edit.addView(seal,screen.grow());
            card.addView(edit); return card;
        }
    }

    private void share(Clips.Clip clip) {
        Intent send = new Intent(Intent.ACTION_SEND).setType("audio/wav")
                .putExtra(Intent.EXTRA_STREAM,clip.uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newUri(screen.getContentResolver(),clip.name,clip.uri));
        try { screen.startActivity(Intent.createChooser(send,Lang.text(R.string.share_recording))); }
        catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(screen,Lang.text(R.string.no_app_available_to_share_this_file),Toast.LENGTH_SHORT).show();
        }
    }
    private void play(Clips.Clip clip) {
        boolean wasPlaying = clip.uri.equals(listening);
        endPlayback();
        if (wasPlaying) return;
        MediaPlayer next = new MediaPlayer(); player = next; listening = clip.uri;
        try {
            next.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE).build());
            next.setDataSource(screen,clip.uri);
            next.setOnPreparedListener(p -> {
                if (player == p) {
                    try { p.start(); } catch (IllegalStateException e) { playbackError(); }
                }
            });
            next.setOnCompletionListener(p -> { if (player == p) endPlayback(); });
            next.setOnErrorListener((p,what,extra) -> {
                if (player == p) playbackError(); return true;
            });
            next.prepareAsync(); adapter.notifyDataSetChanged();
        } catch (IOException | RuntimeException error) { playbackError(); }
    }
    private void playbackError() {
        endPlayback(); Toast.makeText(screen,Lang.text(R.string.file_unavailable_for_playback),Toast.LENGTH_SHORT).show();
    }
    void endPlayback() {
        if (player == null) return;
        MediaPlayer old = player; player = null; listening = null;
        old.release(); adapter.notifyDataSetChanged();
    }
    void close() {
        closed = true;
        screen.getContentResolver().unregisterContentObserver(changes);
        main.removeCallbacks(reload); io.shutdownNow(); endPlayback();
    }
}
