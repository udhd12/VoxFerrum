package dev.voxferrum;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.util.ArrayList;
import java.util.Locale;

public final class MainActivity extends Activity {
    static final int FULL = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int AUDIO = 31, NOTIFY = 32, PROJECT = 33;
    private final Handler loop = new Handler(Looper.getMainLooper());
    final SharedPreferences prefs() { return getSharedPreferences("vox", MODE_PRIVATE); }
    Skin skin;
    private LinearLayout root, body, controls;
    private TextView counter, indicator, gainReadout, message;
    private Button primary, stop, sourceButton, restorePlayback;
    private long pendingRestore;
    private static final String[] SOURCE_IDS = {"playback","mic","mix"};
    private String[] sourceNames() { return new String[]{Lang.text(R.string.app_audio),Lang.text(R.string.microphone),Lang.text(R.string.apps_microphone)}; }
    private String pendingSource;
    private boolean resumedForMic, startMicOnResume;
    private Intent acceptedProjection;
    private LinearLayout micPanel;
    private TextView micDevice;
    private ProgressBar micLevel;
    private Button micRouteButton;
    private Switch priorityMic;
    private LinearLayout liveActions;
    private ForgeGauge gauge;
    private SignalArt trace;
    private Archive archive;
    private BootSettings bootSettings;
    private int tab;
    private boolean visible, starting, requested;
    private String lastMessage;
    private long startUntil;
    private final ArrayList<Button> durations = new ArrayList<>();
    private final Runnable poll = new Runnable() {
        public void run() {
            if (!visible) return;
            if (tab == 0) updateDeck(true);
            loop.postDelayed(this, 240);
        }
    };

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (saved != null) {
            tab = saved.getInt("tab");
            requested = saved.getBoolean("requested");
            pendingSource = saved.getString("pendingSource");
            pendingRestore = saved.getLong("pendingRestore");
            startMicOnResume = saved.getBoolean("startMicOnResume");
            if(saved.getBoolean("waitingForVisibleStart")) {
                acceptedProjection=(Intent)getLastNonConfigurationInstance();
                requested=acceptedProjection!=null;
                if(!requested)pendingSource=null;
            }
        }
        drawPage();
        if (saved == null) route(getIntent());
    }
    @Override protected void onNewIntent(Intent intent){
        super.onNewIntent(intent); setIntent(intent); route(intent);
    }
    private void route(Intent intent){
        if(ReplayWidget.ARCHIVE.equals(intent.getAction())) go(1);
        if(ReplayWidget.ARM.equals(intent.getAction()) || (BootReminder.RESUME.equals(intent.getAction()) && BootReminder.consume(this))) {
            go(0); root.post(this::begin);
        }
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("tab", tab); out.putBoolean("requested", requested);
        out.putString("pendingSource", pendingSource);
        out.putLong("pendingRestore", pendingRestore);
        out.putBoolean("startMicOnResume", startMicOnResume);
        out.putBoolean("waitingForVisibleStart", acceptedProjection!=null);
    }
    @Override public Object onRetainNonConfigurationInstance() { return acceptedProjection; }

    @Override protected void onStart() {
        super.onStart(); visible = true; loop.post(poll);
        if (archive != null && tab == 1) archive.refresh();
    }
    @Override protected void onPostResume() {
        super.onPostResume();
        resumedForMic = true;
        if (acceptedProjection!=null) startProjectedWhenVisible();
        else if (startMicOnResume) startMicWhenVisible();
        if (Intent.ACTION_MAIN.equals(getIntent().getAction()) && BootReminder.consume(this)) {
            go(0); root.post(this::begin);
        }
    }
    @Override protected void onPause() {
        resumedForMic = false;
        super.onPause();
    }
    @Override protected void onStop() {
        visible = false; loop.removeCallbacks(poll);
        if (archive != null) archive.endPlayback();
        super.onStop();
    }
    @Override protected void onDestroy() {
        if (archive != null) archive.close();
        super.onDestroy();
    }
    @Override public void onBackPressed() {
        if (tab != 0) go(0); else super.onBackPressed();
    }
    private void go(int page) {
        if (tab == page) return;
        tab = page; drawPage();
    }
    int dp(float value) { return Math.round(getResources().getDisplayMetrics().density * value); }
    LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }
    LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }
    LinearLayout.LayoutParams grow() { return new LinearLayout.LayoutParams(0, WRAP, 1f); }
    void pad(View v, int x, int y) { v.setPadding(dp(x),dp(y),dp(x),dp(y)); }
    void space(LinearLayout box, int h) { box.addView(new View(this), FULL, dp(h)); }
    void gap(LinearLayout r, int w) { r.addView(new View(this), dp(w), 1); }
    void rule(LinearLayout box) { View v = new View(this); v.setBackgroundColor(skin.line); box.addView(v,FULL,dp(1)); }
    TextView text(String value, float sp, int color, Typeface tf) {
        TextView t = new TextView(this);
        t.setText(value); t.setTextColor(color); t.setTextSize(sp); t.setIncludeFontPadding(false);
        if (tf != null) t.setTypeface(tf);
        return t;
    }
    Button button(String title, boolean major, View.OnClickListener click) {
        Button b = new Button(this, null, android.R.attr.borderlessButtonStyle);
        b.setText(title); b.setTextSize(15); b.setAllCaps(false); b.setGravity(Gravity.CENTER);
        b.setMinWidth(0); b.setMinimumWidth(0); b.setMinHeight(dp(48));
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        pad(b,8,9); paintButton(b,major); b.setOnClickListener(click);
        return b;
    }
    void paintButton(Button b, boolean major) {
        b.setTextColor(major ? skin.actionInk : skin.ink);
        if (major && !skin.tide) {
            b.setTypeface(skin.display);
            b.setTextSize(18);
            b.setLetterSpacing(.025f);
            b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x50ffce92),
                    forgePlate(ForgePlate.Finish.ENAMEL), null));
            return;
        }
        b.setBackground(skin.button(major ? skin.action : skin.panel, major ? 0 : skin.line,
                dp(skin.tide ? 14 : 3), dp(1)));
    }
    ForgePlate forgePlate(ForgePlate.Finish finish) {
        return new ForgePlate(finish,getResources().getDisplayMetrics().density);
    }
    LinearLayout plate() {
        LinearLayout p = column(); pad(p,16,16);
        p.setBackground(skin.tide ? skin.surface(skin.panel,skin.line,dp(18),dp(1))
                : forgePlate(ForgePlate.Finish.IRON));
        return p;
    }
    private void drawPage() {
        if (archive != null) { archive.close(); archive = null; }
        skin = new Skin(prefs().getBoolean("tide", false));
        getWindow().setStatusBarColor(0);
        getWindow().setNavigationBarColor(0);
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (skin.tide) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);
        root = column(); root.setBackgroundColor(skin.page);
        root.setOnApplyWindowInsetsListener((v,i) -> {
            v.setPadding(i.getSystemWindowInsetLeft(),i.getSystemWindowInsetTop(),
                         i.getSystemWindowInsetRight(),i.getSystemWindowInsetBottom());
            return i;
        });
        setContentView(root);
        LinearLayout mast = row(); pad(mast, 20, 12);
        if (!skin.tide) {
            mast.setBackgroundColor(0xff291815);
            mast.addView(new SignalArt(this,skin,true),dp(34),dp(34)); gap(mast,9);
        }
        TextView wordmark = text(skin.tide ? "Vox Ferrum" : "VOX FERRUM", 23, skin.ink, skin.display);
        wordmark.setLetterSpacing(skin.tide ? -.035f : .04f);
        mast.addView(wordmark, grow());
        mast.addView(text(skin.tide ? Lang.text(R.string.tide) : Lang.text(R.string.omnissiah), 11, skin.accent, skin.mono));
        root.addView(mast); rule(root);

        LinearLayout frame = column();
        root.addView(frame,new LinearLayout.LayoutParams(FULL,0,1));
        if (tab == 1) {
            body = frame; pad(body,18,14);
            archive = new Archive(this, body);
            if (visible) archive.refresh();
        } else {
            ScrollView scroll = new ScrollView(this);
            scroll.setFillViewport(false); frame.addView(scroll,FULL,FULL);
            body = column(); pad(body,18,18); scroll.addView(body);
            if (tab == 0) deck(); else settings();
        }
        rule(root);
        LinearLayout nav = row(); nav.setBaselineAligned(false); pad(nav,6,5);
        String[] pages = {Lang.text(R.string.replay),Lang.text(R.string.archive),Lang.text(R.string.settings)};
        for (int j = 0; j<pages.length; j++) {
            final int n = j;
            Button b = button(pages[j],false,v -> go(n));
            b.setSelected(tab==j); b.setTextSize(14);
            b.setTextColor(tab==j ? skin.accent : skin.muted);
            b.setBackground(skin.button(tab==j ? skin.plate : skin.page,0,dp(skin.tide ? 20 : 2),1));
            nav.addView(b,grow());
        }
        root.addView(nav); root.requestApplyInsets();
    }
    private int seconds() { return prefs().getInt("seconds",30); }
    private int gain() { return prefs().getInt("gainDb",0); }

    private static String formatDuration(int seconds) {
        return seconds >= 60 && seconds % 60 == 0 ? seconds / 60 + Lang.text(R.string.min) : seconds + Lang.text(R.string.s);
    }

    private void forgeInstrument() {
        FrameLayout shrine = new FrameLayout(this);
        BitmapDrawable icon = (BitmapDrawable)getDrawable(R.drawable.forge_shrine);
        shrine.setBackground(ForgePlate.artwork(icon.getBitmap()));

        LinearLayout face = row();
        face.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xb40f0d0b,0x62100e0b,0x00100e0b}));
        LinearLayout reading = column();
        pad(reading,13,5);
        reading.addView(text(Lang.text(R.string.replay_buffer),14,skin.accent,null));
        counter = text("00:00",43,skin.ink,skin.mono); reading.addView(counter);
        space(reading,5); indicator = text("",13,skin.ink,null); reading.addView(indicator);
        face.addView(reading,new LinearLayout.LayoutParams(0,WRAP,1.13f));
        face.addView(new View(this),new LinearLayout.LayoutParams(0,1,1f));
        int widthDp = getResources().getConfiguration().screenWidthDp;
        face.setMinimumHeight(dp(Math.min(210,Math.max(144,(widthDp-36)*.4f))));
        shrine.addView(face,new FrameLayout.LayoutParams(FULL,WRAP,Gravity.CENTER_VERTICAL));
        gauge=new ForgeGauge(this);
        shrine.addView(gauge,new FrameLayout.LayoutParams(FULL,FULL));
        shrine.setForeground(forgePlate(ForgePlate.Finish.COVER));
        body.addView(shrine,FULL,WRAP);

        LinearLayout meter = row();
        pad(meter,12,5);
        meter.setBackground(forgePlate(ForgePlate.Finish.IRON));
        meter.addView(text(Lang.text(R.string.audio_level),12,skin.muted,null));
        gap(meter,10); trace = new SignalArt(this,skin,false);
        meter.addView(trace,new LinearLayout.LayoutParams(0,dp(30),1f));
        body.addView(meter,FULL,WRAP);
    }

    private void deck() {
        if (skin.tide) tideInstrument(); else forgeInstrument();
        space(body,12);
        replayControls();
    }

    private void tideInstrument() {
        LinearLayout instrument = plate();
        LinearLayout top = row();
        LinearLayout readings = column();
        readings.addView(text(Lang.text(R.string.replay_buffer),14,skin.muted,null));
        counter = text("00:00",44,skin.ink,skin.mono);
        readings.addView(counter); space(readings,2);
        indicator = text("",13,skin.accent,null); readings.addView(indicator);
        top.addView(readings,grow()); top.addView(new SignalArt(this,skin,true),dp(88),dp(88));
        instrument.addView(top); space(instrument,12);
        trace = new SignalArt(this,skin,false); instrument.addView(trace,FULL,dp(40));
        space(instrument,7);
        instrument.addView(text(Lang.text(R.string.audio_level),12,skin.muted,null));
        body.addView(instrument);
    }

    private int sourceIndex(String value) {
        for (int i=0;i<SOURCE_IDS.length;i++) if (SOURCE_IDS[i].equals(value)) return i;
        return 0;
    }
    private String selectedSource() {
        return SOURCE_IDS[sourceIndex(prefs().getString("audioSource", "playback"))];
    }
    private String nextSource() {
        return pendingSource != null ? pendingSource : selectedSource();
    }
    private boolean sourceLocked() {
        return ReplayService.running || requested || starting;
    }
    private void pickSource() {
        if (sourceLocked()) return;
        new VoxMenu(this).setTitle(Lang.text(R.string.audio_source))
                .setSingleChoiceItems(sourceNames(),sourceIndex(selectedSource()),(dialog,index)->{
                    if (!sourceLocked()) {
                        prefs().edit().putString("audioSource",SOURCE_IDS[index]).apply();
                        updateDeck(false);
                    }
                    dialog.dismiss();
                }).setNegativeButton(Lang.text(R.string.cancel),null).show();
    }
    private void replayControls() {
        LinearLayout sourceRow=row();
        sourceRow.addView(text(Lang.text(R.string.source),15,skin.muted,null)); gap(sourceRow,12);
        sourceButton=button("",false,v->pickSource());
        sourceRow.addView(sourceButton,grow()); body.addView(sourceRow); space(body,8);
        micPanel=column(); body.addView(micPanel);
        micRouteButton=button("",false,v->chooseMicRoute()); micPanel.addView(micRouteButton);
        priorityMic=new Switch(this);
        priorityMic.setText(Lang.text(R.string.recording_priority)); priorityMic.setTextColor(skin.ink);
        priorityMic.setMinHeight(dp(48)); priorityMic.setChecked(prefs().getBoolean("priorityMic",false));
        priorityMic.setOnCheckedChangeListener((button,on)->prefs().edit().putBoolean("priorityMic",on).apply());
        if(Build.VERSION.SDK_INT>=30){
            micPanel.addView(priorityMic);
            micPanel.addView(text(Lang.text(R.string.if_another_app_uses_the_microphone_vox),13,skin.muted,null));
        }
        micDevice=text("",14,skin.muted,null); micPanel.addView(micDevice);
        micLevel=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        micLevel.setContentDescription(Lang.text(R.string.microphone_input_level));
        micLevel.setMax(60); micLevel.setProgressTintList(ColorStateList.valueOf(skin.accent));
        micPanel.addView(micLevel,FULL,dp(10)); space(micPanel,8);
        primary = button(Lang.text(R.string.enable_replay),true,v -> {
            if (ReplayService.running) ReplayService.save(this); else begin();
        });
        body.addView(primary,FULL,WRAP);
        stop = button(Lang.text(R.string.stop_capture),false,v -> ReplayService.stop(this));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(FULL,WRAP); sp.topMargin=dp(6);
        liveActions=row();
        liveActions.addView(button(Lang.text(R.string.mark),false,v -> ReplayService.mark(this)),grow());
        gap(liveActions,6); liveActions.addView(stop,grow());
        body.addView(liveActions, sp);
        restorePlayback = button(Lang.text(R.string.restore_app_audio),false,v -> {
            long id = ReplayService.restoreId;
            if (id == 0 || requested) return;
            pendingRestore = id;
            projection();
        });
        body.addView(restorePlayback,FULL,WRAP);
        message = text("",14,skin.muted,null); message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        space(body,8); body.addView(message);
        controls = column(); body.addView(controls);
        space(controls,15); rule(controls); space(controls,16);
        controls.addView(text(Lang.text(R.string.replay_length),18,skin.ink,skin.display)); space(controls,8);
        durations.clear();
        addDurations(15,30,60,120);
        space(controls,5);
        addDurations(300,600,900);
        selectDuration(); space(controls,18);
        LinearLayout gainLine = row();
        gainLine.addView(text(Lang.text(R.string.clip_gain),18,skin.ink,skin.display), grow());
        gainReadout = text(gainText(gain()),16,skin.accent,skin.mono); gainLine.addView(gainReadout);
        controls.addView(gainLine);
        SeekBar level = new SeekBar(this); level.setMax(18); level.setProgress(gain());
        level.setContentDescription(Lang.text(R.string.saved_audio_gain_in_decibels));
        level.setThumbTintList(ColorStateList.valueOf(skin.accent));
        level.setProgressTintList(ColorStateList.valueOf(skin.accent));
        level.setProgressBackgroundTintList(ColorStateList.valueOf(skin.line));
        level.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar b) {}
            public void onStopTrackingTouch(SeekBar b) {}
            public void onProgressChanged(SeekBar b,int value,boolean user) {
                if (user) { prefs().edit().putInt("gainDb",value).apply(); gainReadout.setText(gainText(value)); }
            }
        });
        controls.addView(level,FULL,dp(48));
        lastMessage = null;
        updateDeck(false);
    }

    private void chooseMicRoute() {
        if (sourceLocked()) return;
        new VoxMenu(this).setTitle(Lang.text(R.string.microphone_input))
            .setSingleChoiceItems(new String[]{Lang.text(R.string.let_android_choose),Lang.text(R.string.built_in_phone_microphone)},
                prefs().getBoolean("preferBuiltinMic",false)?1:0,(dialog,index)->{
                    if(!sourceLocked()) prefs().edit().putBoolean("preferBuiltinMic",index==1).apply();
                    dialog.dismiss();updateDeck(false);
                }).setNegativeButton(Lang.text(R.string.cancel),null).show();
    }

    private void showMicInput(String mode,boolean active,boolean locked) {
        boolean hasMic=!"playback".equals(mode);
        micPanel.setVisibility(hasMic ? View.VISIBLE : View.GONE);
        if(!hasMic)return;
        String preferred=prefs().getBoolean("preferBuiltinMic",false)?Lang.text(R.string.built_in_microphone):Lang.text(R.string.android_default);
        String routeTitle=Lang.text(R.string.input)+preferred;
        if(!routeTitle.contentEquals(micRouteButton.getText()))micRouteButton.setText(routeTitle);
        micRouteButton.setEnabled(!locked);
        priorityMic.setEnabled(!locked);
        micDevice.setVisibility(active?View.VISIBLE:View.GONE);
        micLevel.setVisibility(active?View.VISIBLE:View.GONE);
        if(!active)return;
        String device=ReplayService.micRoute;
        String caption=device.isEmpty()?Lang.text(R.string.microphone_signal):Lang.text(R.string.microphone_signal_alt)+device;
        if(!caption.contentEquals(micDevice.getText()))micDevice.setText(caption);
        int peak=ReplayService.micPeak;
        int dB=peak<=0 ? -60 : (int)Math.round(20*Math.log10(peak/32768d));
        micLevel.setProgress(Math.max(0,Math.min(60,60+dB)));
    }
    private void addDurations(int... seconds) {
        LinearLayout options = row(); options.setBaselineAligned(false);
        for (int sec : seconds) {
            Button b = button(formatDuration(sec),false,v -> {
                prefs().edit().putInt("seconds",sec).apply();
                selectDuration();
                updateDeck(false);
            });
            b.setTag(sec);
            if (options.getChildCount() > 0) gap(options,5);
            options.addView(b,grow());
            durations.add(b);
        }
        controls.addView(options);
    }
    private String gainText(int value) { return (value>0 ? "+" : "") + value + Lang.text(R.string.db); }
    private void selectDuration() {
        for (Button b : durations) {
            boolean chosen = (int)b.getTag() == seconds();
            b.setSelected(chosen); b.setTextColor(chosen ? skin.accent : skin.muted);
            b.setBackground(skin.button(chosen ? skin.plate : skin.page,chosen ? skin.accent : skin.line,
                    dp(skin.tide ? 14 : 2),dp(1)));
        }
    }
    private void updateDeck(boolean advance) {
        if (counter == null) return;
        boolean active = ReplayService.running;
        if (active || android.os.SystemClock.uptimeMillis()>startUntil) starting = false;
        String mode = active ? ReplayService.sessionSource : nextSource();
        String sourceName = sourceNames()[sourceIndex(mode)];
        boolean locked = sourceLocked();
        String sourceTitle = locked ? sourceName : sourceName + "  ▾";
        if (!sourceTitle.contentEquals(sourceButton.getText())) {
            sourceButton.setText(sourceTitle);
            sourceButton.setContentDescription(Lang.text(R.string.audio_source_alt) + sourceName
                    + (locked ? Lang.text(R.string.stop_capture_to_change_it) : Lang.text(R.string.change)));
        }
        sourceButton.setEnabled(!locked);
        showMicInput(mode,active,locked);
        int ms = active ? Math.min(seconds()*1000,ReplayService.bufferedMs) : 0;
        int whole = Math.max(0, ms / 1000);
        counter.setText(String.format(Locale.ROOT,"%02d:%02d",whole/60,whole%60));
        indicator.setText(active ? Lang.text(R.string.live) + formatDuration(seconds()) : Lang.text(R.string.capture_off));
        if (advance && active) trace.push(ReplayService.peakPercent); else if (!active) trace.resetTrace();
        primary.setText(active ? (ReplayService.saving ? Lang.text(R.string.saving) : Lang.text(R.string.save_replay)) :
                (starting || requested ? Lang.text(R.string.starting) : Lang.text(R.string.enable_replay)));
        primary.setEnabled(active ? !ReplayService.saving && ms>0 : !starting && !requested);
        primary.setAlpha(primary.isEnabled() ? 1f : .55f);
        liveActions.setVisibility(active ? View.VISIBLE : View.GONE);
        restorePlayback.setVisibility(active && ReplayService.restoreId != 0 ? View.VISIBLE : View.GONE);
        restorePlayback.setEnabled(!requested);
        if(!skin.tide && gauge!=null) gauge.setState(active,ms/(seconds()*1000f),ReplayService.savedAt);
        String s = Lang.text(ReplayService.status);
        if (s == null || s.isEmpty() || Lang.text(R.string.buffer_off).equals(s) || Lang.text(R.string.buffer_active).equals(s)) {
            if (active) s = Lang.text(R.string.tap_save_in_the_notification_to_keep);
            else if ("mic".equals(mode)) s = Lang.text(R.string.the_replay_will_capture_your_voice_and);
            else if ("mix".equals(mode)) s = Lang.text(R.string.approve_app_audio_capture_if_android_stops);
            else s = Lang.text(R.string.replay_records_audio_from_apps_that_allow);
            if (active && Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                s = Lang.text(R.string.enable_notifications_in_settings_to_save_from);
        }
        if(active && ReplayService.silent) s=ReplayService.silenceMessage();
        if(active && !ReplayService.micWarning.isEmpty()) s=ReplayService.micWarning;
        if (!s.equals(lastMessage)) { lastMessage = s; message.setText(s); }
    }

    private void settings() {
        body.addView(text(Lang.text(R.string.settings),29,skin.ink,skin.display)); space(body,12);
        body.addView(button(Lang.text(R.string.language) + " · " + (Lang.english() ? "English" : "Русский"), false, v ->
            new VoxMenu(this).setTitle("Русский / English").setSingleChoiceItems(
                new String[]{"Русский", "English"}, Lang.english() ? 1 : 0, (dialog, index) -> {
                    dialog.dismiss();
                    Lang.select(this, index == 1);
                    drawPage();
                }).setNegativeButton(Lang.text(R.string.cancel), null).show()));
        space(body,12);
        bootSettings=new BootSettings(this); body.addView(bootSettings);
        space(body,16); rule(body); space(body,16);
        body.addView(text(Lang.text(R.string.appearance),18,skin.ink,skin.display)); space(body,9);
        themeChoice(false,Lang.text(R.string.forge_of_the_omnissiah),Lang.text(R.string.tech_priest_engraving_brass_and_red_enamel));
        space(body,7);
        themeChoice(true,Lang.text(R.string.tide_alt),Lang.text(R.string.light_paper_and_sea_green));
        space(body,20); rule(body); space(body,16);
        body.addView(text("Telegram",20,skin.ink,skin.display)); space(body,6);
        body.addView(text(Lang.text(R.string.connect_your_bot_to_send_selected_clips),
                15,skin.muted,null)); space(body,10);
        body.addView(button(Lang.text(R.string.configure_bot),false,v -> Telegram.showSettings(this)));
        space(body,20); rule(body); space(body,16);
        body.addView(text(Lang.text(R.string.quick_access),20,skin.ink,skin.display)); space(body,8);
        body.addView(button(Lang.text(R.string.add_widget),false,v -> addWidget()));
        space(body,6); body.addView(button(Lang.text(R.string.add_quick_settings_tile),false,v -> addTile()));
        space(body,16);
        body.addView(text(Lang.text(R.string.audio_capture),20,skin.ink,skin.display)); space(body,6);
        body.addView(text(Lang.text(R.string.choose_a_source_on_the_replay_tab),15,skin.muted,null));
        space(body,12); body.addView(button(Lang.text(R.string.replay_notifications),false,v -> {
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName());
            startActivity(i);
        }));
    }

    private void addWidget() {
        android.appwidget.AppWidgetManager manager=android.appwidget.AppWidgetManager.getInstance(this);
        if(manager.isRequestPinAppWidgetSupported() && manager.requestPinAppWidget(
                new android.content.ComponentName(this,ReplayWidget.class),null,null)) return;
        new VoxMenu(this).setMessage(Lang.text(R.string.touch_and_hold_an_empty_area_on))
                .setPositiveButton(Lang.text(R.string.got_it),null).show();
    }
    private void addTile() {
        if(Build.VERSION.SDK_INT>=33){
            getSystemService(android.app.StatusBarManager.class).requestAddTileService(
                    new android.content.ComponentName(this,ReplayTile.class),Lang.text(R.string.audio_replay),
                    android.graphics.drawable.Icon.createWithResource(this,R.drawable.ic_wave),
                    getMainExecutor(), result -> {
                        if(result == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED)
                            Toast.makeText(this,Lang.text(R.string.tile_already_added),Toast.LENGTH_SHORT).show();
                        else if(result >= 1000) tileHelp();
                    });
        } else tileHelp();
    }
    private void tileHelp() {
        new VoxMenu(this).setMessage(Lang.text(R.string.open_quick_settings_tap_edit_and_drag))
                .setPositiveButton(Lang.text(R.string.got_it),null).show();
    }
    private void themeChoice(boolean tide,String name,String desc) {
        Skin sample = new Skin(tide);
        LinearLayout choice = row(); pad(choice,12,8);
        boolean selected = skin.tide == tide;
        choice.setBackground(sample.button(sample.panel,selected ? skin.accent : sample.line,
                dp(tide ? 14 : 3),dp(selected ? 2 : 1)));
        choice.addView(new SignalArt(this,sample,true),dp(47),dp(47)); gap(choice,12);
        LinearLayout description = column();
        description.addView(text(name,18,sample.ink,sample.display)); space(description,4);
        description.addView(text(desc,13,sample.muted,null));
        choice.addView(description,grow());
        if (selected) choice.addView(text("✓",20,sample.accent,null));
        choice.setFocusable(true); choice.setSelected(selected);
        choice.setContentDescription(name + ". " + desc + (selected ? Lang.text(R.string.selected) : ""));
        choice.setOnClickListener(v -> {
            if (!selected) { prefs().edit().putBoolean("tide",tide).apply(); drawPage(); }
        });
        body.addView(choice,FULL,WRAP);
    }

    private void begin() {
        if (requested || starting || ReplayService.running) return;
        pendingRestore = 0;
        BootReminder.clear(this);
        pendingSource = selectedSource();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requested = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},AUDIO);
        } else notificationsThenCapture();
    }
    private void notificationsThenCapture() {
        if (Build.VERSION.SDK_INT>=33 && !prefs().getBoolean("askedNotification",false)
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) {
            requested = true;
            prefs().edit().putBoolean("askedNotification",true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFY);
        } else startForSource();
    }
    private void startForSource() {
        if ("mic".equals(nextSource())) startMicWhenVisible();
        else projection();
    }
    private void startMicWhenVisible() {
        // Microphone access is a while-in-use permission. A returning permission
        // dialog may deliver its result before this activity resumes.
        if (!resumedForMic) {
            startMicOnResume = true;
            requested = true;
            return;
        }
        startMicOnResume = false;
        requested = false;
        try {
            ReplayService.startMicrophone(this);
            starting = true; startUntil = android.os.SystemClock.uptimeMillis()+2000;
        } catch (RuntimeException e) {
            Toast.makeText(this,Lang.text(R.string.could_not_start_the_microphone_open_the),Toast.LENGTH_LONG).show();
        } finally { pendingSource = null; }
    }
    private void projection() {
        requested = true;
        try {
            MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
            Intent request = Build.VERSION.SDK_INT>=34
                    ? manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                    : manager.createScreenCaptureIntent();
            startActivityForResult(request,PROJECT);
        } catch (RuntimeException e) {
            requested = false;
            pendingRestore = 0;
            pendingSource = null;
            Toast.makeText(this,Lang.text(R.string.system_capture_is_unavailable_on_this_device),Toast.LENGTH_LONG).show();
        }
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==BootSettings.PERMISSION_REQUEST){if(bootSettings!=null)bootSettings.refresh();return;}
        if (request!=AUDIO && request!=NOTIFY) return;
        requested = false;
        if (results.length==0) { pendingSource = null; return; }
        if (request == NOTIFY) { startForSource(); return; }
        if (results[0] == PackageManager.PERMISSION_GRANTED) notificationsThenCapture();
        else {
            pendingSource = null;
            new VoxMenu(this).setTitle(Lang.text(R.string.allow_audio_access))
                .setMessage(Lang.text(R.string.allow_microphone_access_in_app_permissions_android))
                .setNegativeButton(Lang.text(R.string.close),null).setPositiveButton(Lang.text(R.string.settings),(dlg,which) -> {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.fromParts("package",getPackageName(),null)));
                }).show();
        }
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if (request != PROJECT) return;
        requested = false;
        if (result != RESULT_OK || data == null) {
            boolean restoring = pendingRestore != 0;
            pendingRestore = 0;
            pendingSource = null;
            Toast.makeText(this,restoring ? Lang.text(R.string.app_audio_restore_canceled) : Lang.text(R.string.capture_canceled),Toast.LENGTH_SHORT).show(); return;
        }
        acceptedProjection=data;
        requested=true;
        startProjectedWhenVisible();
    }
    private void startProjectedWhenVisible() {
        if(!resumedForMic || acceptedProjection==null)return;
        Intent consent=acceptedProjection;
        acceptedProjection=null;
        requested=false;
        try {
            if (pendingRestore != 0) {
                if (ReplayService.restoreId == pendingRestore) ReplayService.restore(this,pendingRestore,consent);
                return;
            }
            ReplayService.start(this,nextSource(),RESULT_OK,consent);
            starting = true; startUntil = android.os.SystemClock.uptimeMillis()+2000;
        } catch (RuntimeException e) {
            Toast.makeText(this,Lang.text(R.string.could_not_start_capture_open_the_app),
                    Toast.LENGTH_LONG).show();
        } finally { pendingSource = null; pendingRestore = 0; }
    }
}
