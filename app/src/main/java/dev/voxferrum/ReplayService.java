package dev.voxferrum;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;

import java.io.IOException;
import java.io.File;

public class ReplayService extends Service implements SharedPreferences.OnSharedPreferenceChangeListener {
    public static final String CHANGED = "dev.voxferrum.REPLAY_CHANGED";
    public static volatile boolean running, saving;
    public static volatile int bufferedMs, peakPercent;
    /** Raw absolute PCM16 peaks, before mixing or export gain. */
    public static volatile int micPeak, playbackPeak;
    public static volatile String micRoute = "", micWarning = "";
    public static volatile boolean silent;
    public static volatile long savedAt, markedAt;
    private final Runnable resetWidgetMessage=() -> ReplayWidget.refresh(this);
    private void brieflyUpdateWidget() {
        main.removeCallbacks(resetWidgetMessage);
        main.postDelayed(resetWidgetMessage, 2300);
    }
    static final String SILENCE_MESSAGE = "Уже 12 секунд без звука. Проверьте воспроизведение и разрешает ли игра захват.";
    public static volatile String sessionSource = "playback";
    static volatile long restoreId;
    static final String MIC_CONTINUES = "Звук приложений отключён. Микрофон продолжает запись.";

    public static String silenceMessage() {
        switch (sessionSource) {
            case "mic": return "Уже 12 секунд без звука. Проверьте микрофон и его системный выключатель.";
            case "mix": return "Уже 12 секунд без звука. Проверьте микрофон и воспроизведение в приложении.";
            default: return SILENCE_MESSAGE;
        }
    }

    static final String MARK = "mark";
    public static volatile String status = "Буфер выключен";
    static final String START = "start", SAVE = "save", STOP = "stop";
    private static final String CHANNEL = "vox_capture";
    private static final int NOTIFICATION = 72;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Session session;
    private SharedPreferences prefs;
    private boolean foreground;

    private static final class Session {
        final RollingAudio audio;
        Session(File cache) { audio = new RollingAudio(cache); }
        MediaProjection projection;
        MediaProjection.Callback callback;
        CaptureInputs inputs;
    }

    public static void start(Context context, int resultCode, Intent data) {
        String source = context.getSharedPreferences("vox", MODE_PRIVATE).getString("audioSource", "playback");
        start(context, source, resultCode, data);
    }

    public static void start(Context context, String source, int resultCode, Intent data) {
        String projected = "mix".equals(source) ? "mix" : "playback";
        context.startForegroundService(new Intent(context, ReplayService.class).setAction(START)
                .putExtra("source", projected).putExtra("result", resultCode).putExtra("consent", data));
    }

    public static void startMicrophone(Context context) {
        context.startForegroundService(new Intent(context, ReplayService.class).setAction(START)
                .putExtra("source", "mic"));
    }

    public static void save(Context context) {
        if (running) context.startService(new Intent(context, ReplayService.class).setAction(SAVE));
    }

    static void mark(Context context) {
        if (running) context.startService(new Intent(context, ReplayService.class).setAction(MARK));
    }

    public static void stop(Context context) {
        if (running) context.startService(new Intent(context, ReplayService.class).setAction(STOP));
    }

    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("vox", MODE_PRIVATE);
        prefs.registerOnSharedPreferenceChangeListener(this);
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "Аудиоповтор", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if ("restore".equals(action)) {
            if (session != null && restoreId != 0 && restoreId == intent.getLongExtra("restoreId", 0))
                restorePlayback(session, intent);
            else if (session == null && !saving) stopSelf();
        } else if (START.equals(action)) {
            if (session == null) begin(intent);
        } else if (SAVE.equals(action) && session != null) {
            saveSnapshot();
        } else if (MARK.equals(action) && session != null) {
            if (session.audio.mark()) {
                markedAt = android.os.SystemClock.elapsedRealtime();
                brieflyUpdateWidget();
                status = "Метка добавлена";
                changed();
            }
        } else if (STOP.equals(action)) {
            end("Буфер выключен");
        } else if (session == null && !saving) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void begin(Intent intent) {
        Session next = new Session(getCacheDir());
        session = next;
        String source = intent.getStringExtra("source");
        sessionSource = "mic".equals(source) ? "mic" : "mix".equals(source) ? "mix" : "playback";
        boolean withPlayback = !"mic".equals(sessionSource);
        boolean withMicrophone = !"playback".equals(sessionSource);
        CaptureInputs inputs = null;
        try {
            updateForeground(withPlayback, withMicrophone);
            foreground = true;
            if (withPlayback) attachProjection(next, intent);
            inputs = CaptureInputs.open(this, next.projection, withMicrophone,
                    prefs.getBoolean("preferBuiltinMic", false), playbackError(next));
            CaptureInputs ready = inputs;
            next.inputs = ready;
            running = true;
            status = "Буфер включён";
            new Thread(() -> read(next, ready), "vox-audio").start();
            inputs = null; // The reader now owns release.
            BootReminder.clear(this);
            changed();
        } catch (RuntimeException e) {
            if (inputs != null) {
                next.inputs = null;
                inputs.close();
            }
            String failure;
            if (e instanceof SecurityException) failure = "Android не разрешил захват. Проверьте разрешения и повторите.";
            else if (e instanceof CaptureInputs.MicUnavailable) failure = "Не удалось открыть микрофон. Проверьте подключение гарнитуры и доступ к микрофону в настройках Android.";
            else if ("mix".equals(sessionSource)) failure = "Не удалось открыть оба источника. Выберите один источник на вкладке «Повтор».";
            else if ("mic".equals(sessionSource)) failure = "Не удалось открыть микрофон. Проверьте, доступен ли он.";
            else failure = "Не удалось открыть системный звук. Запустите буфер снова.";
            end(failure);
        }
    }

    private void updateForeground(boolean playback, boolean mic) {
        int type = playback ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION : 0;
        if (mic && Build.VERSION.SDK_INT >= 30) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        startForeground(NOTIFICATION, notification(), type);
    }

    static void restore(Context context, long id, Intent consent) {
        context.startService(new Intent(context, ReplayService.class).setAction("restore")
                .putExtra("restoreId", id).putExtra("result", Activity.RESULT_OK).putExtra("consent", consent));
    }

    private void restorePlayback(Session current, Intent intent) {
        try {
            updateForeground(true, true);
            attachProjection(current, intent);
            current.inputs.attachPlayback(current.projection, playbackError(current));
            restoreId = 0;
            sessionSource = "mix";
            status = "Буфер включён";
        } catch (RuntimeException unavailable) {
            releaseProjection(current);
            updateForeground(false, true);
            status = "Не удалось вернуть звук приложений. Микрофон продолжает запись.";
        }
        changed();
    }

    private Runnable playbackError(Session current) {
        MediaProjection projection = current.projection;
        return () -> main.post(() -> projectionLost(current, projection));
    }

    private void projectionLost(Session current, MediaProjection projection) {
        if (session != current || current.projection != projection || projection == null) return;
        if (!"mix".equals(sessionSource)) {
            end("Android завершил захват. Запустите буфер снова.");
            return;
        }
        current.inputs.detachPlayback();
        releaseProjection(current);
        sessionSource = "mic";
        restoreId = android.os.SystemClock.elapsedRealtimeNanos();
        status = MIC_CONTINUES;
        updateForeground(false, true);
        changed();
    }

    private void attachProjection(Session next, Intent intent) {
        Intent consent = intent.getParcelableExtra("consent");
        if (intent.getIntExtra("result", Activity.RESULT_CANCELED) != Activity.RESULT_OK || consent == null)
            throw new SecurityException("Missing consent");
        next.projection = getSystemService(MediaProjectionManager.class).getMediaProjection(Activity.RESULT_OK, consent);
        if (next.projection == null) throw new SecurityException("No projection");
        MediaProjection projection = next.projection;
        next.callback = new MediaProjection.Callback() {
            @Override public void onStop() {
                projectionLost(next, projection);
            }
        };
        next.projection.registerCallback(next.callback, main);
    }

    private void read(Session current, CaptureInputs inputs) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            short[] samples = new short[RollingAudio.BLOCK_SAMPLES];
            long quietFrames = 0;
            long quietMicFrames = 0;
            long nextRouteCheck = 0;
            String policyWarning = "";
            String route = "";
            String previousMicWarning = "";
            boolean withMic = !"playback".equals(sessionSource);
            boolean wasSilent = false;
            while (session == current && inputs.read(samples)) {
                int count = samples.length;
                current.audio.append(samples, count, seconds());
                int peak = 0;
                for (int i = 0; i < count; i++) peak = Math.max(peak, Math.abs((int) samples[i]));
                quietFrames = peak <= 4 ? quietFrames + count / RollingAudio.CHANNELS : 0;
                boolean nowSilent = quietFrames >= 12L * RollingAudio.RATE;
                if (withMic) {
                    quietMicFrames = inputs.micPeak <= 4 ? quietMicFrames + count / RollingAudio.CHANNELS : 0;
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (now >= nextRouteCheck) {
                        nextRouteCheck = now + 1000;
                        try {
                            route = inputs.microphoneRoute();
                            policyWarning = inputs.microphonePolicyWarning();
                        } catch (RuntimeException unavailable) {
                            // Routing information is advisory. Keep audio that the recorder still delivers.
                            policyWarning = "";
                        }
                    }
                }
                String warning = !policyWarning.isEmpty() ? policyWarning :
                        quietMicFrames >= 5L * RollingAudio.RATE
                                ? "Микрофон не даёт заметного сигнала. Скажите несколько слов и проверьте выбранный вход." : "";
                if (nowSilent != wasSilent || !warning.equals(previousMicWarning)) {
                    wasSilent = nowSilent;
                    previousMicWarning = warning;
                    main.post(() -> {
                        if (session == current) { silent = nowSilent; micWarning = warning; changed(); }
                    });
                }
                synchronized (this) {
                    if (session == current) {
                        bufferedMs = current.audio.milliseconds();
                        peakPercent = Math.round(peak * 100f / 32768);
                        micPeak = inputs.micPeak;
                        playbackPeak = inputs.playbackPeak;
                        micRoute = route;
                    }
                }
            }
        } catch (IOException e) {
            main.post(() -> {
                if (session == current) end("Не удалось записать буфер. Проверьте свободное место на телефоне.");
            });
        } catch (RuntimeException e) {
            main.post(() -> {
                if (session == current) end("Захват звука прервался. Запустите буфер снова.");
            });
        } finally {
            current.audio.close();
            inputs.close();
        }
    }

    private int seconds() {
        return Math.max(1, Math.min(RollingAudio.MAX_SECONDS, prefs.getInt("seconds", 30)));
    }

    private void saveSnapshot() {
        if (saving) return;
        RollingAudio.Snapshot snapshot = session.audio.last(seconds());
        if (snapshot.samples == 0) {
            status = "Буфер пока пуст";
            changed();
            return;
        }
        int gain = Math.max(0, Math.min(18, prefs.getInt("gainDb", 0)));
        saving = true;
        status = "Сохраняется повтор…";
        changed();
        new Thread(() -> {
            boolean success = false;
            try (RollingAudio.Snapshot clip = snapshot) {
                Clips.write(getApplicationContext(), clip, gain);
                success = true;
            } catch (RuntimeException | IOException ignored) {
                // Report a local, actionable error without exposing paths.
            }
            final String result = success ? "Повтор сохранён в Music/VoxFerrum" : "Не удалось сохранить WAV. Проверьте свободное место.";
            final boolean didSave = success;
            main.post(() -> {
                if (didSave) {
                    savedAt = android.os.SystemClock.elapsedRealtime();
                    brieflyUpdateWidget();
                    android.os.Vibrator vibrator = getSystemService(android.os.Vibrator.class);
                    if (vibrator != null && vibrator.hasVibrator())
                        vibrator.vibrate(android.os.VibrationEffect.createOneShot(45, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
                }
                saving = false;
                status = result;
                changed();
                if (session == null) finishService();
            });
        }, "vox-export").start();
    }

    private void end(String message) {
        releaseSession();
        status = message;
        changed();
        if (!saving) finishService();
    }

    private void releaseSession() {
        Session previous;
        synchronized (this) {
            previous = session;
            session = null;
            restoreId = 0;
            running = false;
            silent = false;
            bufferedMs = peakPercent = 0;
            micPeak = playbackPeak = 0;
            micRoute = micWarning = "";
        }
        if (previous == null) return;
        if (previous.inputs != null) previous.inputs.stop();
        releaseProjection(previous);
    }

    private void releaseProjection(Session current) {
        MediaProjection projection = current.projection;
        if (projection == null) return;
        current.projection = null;
        if (current.callback != null) projection.unregisterCallback(current.callback);
        current.callback = null;
        projection.stop();
    }

    private void finishService() {
        foreground = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private Notification notification() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        String warning = !micWarning.isEmpty() ? micWarning : silent ? silenceMessage() : "";
        Notification.Builder note = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_wave)
                .setContentTitle("Vox Ferrum · " + (restoreId != 0 ? "только микрофон" : "mic".equals(sessionSource) ? "микрофон"
                        : "mix".equals(sessionSource) ? "приложения + микрофон" : "звук приложений"))
                .setContentText(saving ? "Сохранение WAV…" : warning.isEmpty() ? status : warning)
                .setShowWhen(false).setOnlyAlertOnce(true).setOngoing(true);
        if (launch != null) note.setContentIntent(PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE));
        if (!warning.isEmpty() && !saving) note.setStyle(new Notification.BigTextStyle().bigText(warning));
        if (session != null) {
            int length = seconds();
            String duration = length % 60 == 0 ? (length / 60) + " мин" : length + " с";
            if (!saving) note.addAction(new Notification.Action.Builder(null, "Сохранить " + duration, action(SAVE)).build());
            note.addAction(new Notification.Action.Builder(null, "Метка", action(MARK)).build());
            note.addAction(new Notification.Action.Builder(null, "Остановить", action(STOP)).build());
        }
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            note.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return note.build();
    }

    private PendingIntent action(String action) {
        return PendingIntent.getService(this, 0, new Intent(this, ReplayService.class).setAction(action), PendingIntent.FLAG_IMMUTABLE);
    }

    private void changed() {
        if (foreground) getSystemService(NotificationManager.class).notify(NOTIFICATION, notification());
        ReplayWidget.refresh(this);
        sendBroadcast(new Intent(CHANGED).setPackage(getPackageName()));
    }

    @Override public void onSharedPreferenceChanged(SharedPreferences preferences, String key) {
        if (foreground && "seconds".equals(key)) changed();
    }

    @Override public void onDestroy() {
        releaseSession();
        main.removeCallbacks(resetWidgetMessage);
        prefs.unregisterOnSharedPreferenceChangeListener(this);
        foreground = false;
        changed();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
