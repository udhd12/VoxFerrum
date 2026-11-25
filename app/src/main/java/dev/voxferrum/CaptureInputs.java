package dev.voxferrum;

import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.content.Context;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.os.Process;

import java.util.Locale;

/** Mixed capture uses the microphone clock. Inactive playback contributes silence. */
final class CaptureInputs {
    private static final int[] MIC_RATES = {48_000, 44_100, 16_000};
    private final AudioManager audioManager;
    private final boolean preferBuiltin;
    private final boolean priorityMic;
    private AudioRecord playback;
    private Runnable playbackLost;
    private AudioRecord microphone;
    private volatile boolean stopped;
    private final short[] voice = new short[RollingAudio.BLOCK_SAMPLES / RollingAudio.CHANNELS];
    private short[] nativeVoice;
    private final MonoBlockConverter converter = new MonoBlockConverter();
    int micPeak, playbackPeak;

    static final class MicUnavailable extends IllegalStateException {
        MicUnavailable(RuntimeException cause) { super(cause); }
    }

    private CaptureInputs(Context context, boolean preferBuiltin) {
        audioManager = context.getSystemService(AudioManager.class);
        this.preferBuiltin = preferBuiltin;
        priorityMic = context.getSharedPreferences("vox", Context.MODE_PRIVATE).getBoolean("priorityMic", false);
    }

    static CaptureInputs open(Context context, MediaProjection projection, boolean withMicrophone, boolean preferBuiltin, Runnable playbackLost) {
        CaptureInputs inputs = new CaptureInputs(context, preferBuiltin);
        try {
            if (projection != null) inputs.attachPlayback(projection, playbackLost);
            if (withMicrophone) inputs.openMicrophone();
            return inputs;
        } catch (RuntimeException failure) {
            inputs.close();
            throw failure;
        }
    }

    synchronized void attachPlayback(MediaProjection projection, Runnable lost) {
        if (stopped || playback != null) throw new IllegalStateException("Capture is unavailable");
        AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .excludeUid(Process.myUid()).build();
        AudioRecord candidate = builder(AudioFormat.CHANNEL_IN_STEREO, RollingAudio.RATE)
                .setAudioPlaybackCaptureConfig(config).build();
        try {
            start(candidate);
            playback = candidate;
            playbackLost = lost;
        } finally {
            if (playback != candidate) candidate.release();
        }
    }

    synchronized void detachPlayback() {
        if (playback != null) {
            stop(playback);
            playback.release();
            playback = null;
        }
        playbackLost = null;
    }

    private void openMicrophone() {
        AudioDeviceInfo builtin = null;
        if (preferBuiltin) {
            for (AudioDeviceInfo input : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                if (input.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) { builtin = input; break; }
            }
        }
        RuntimeException lastError = null;
        for (int source : new int[]{MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.DEFAULT}) {
            for (int rate : MIC_RATES) {
                try {
                    openMicrophone(source, rate, builtin);
                    return;
                } catch (IllegalArgumentException | UnsupportedOperationException | IllegalStateException failure) {
                    lastError = failure;
                }
            }
        }
        throw new MicUnavailable(lastError);
    }

    private void openMicrophone(int source, int rate, AudioDeviceInfo builtin) {
        AudioRecord candidate = null;
        try {
            AudioRecord.Builder micBuilder = builder(AudioFormat.CHANNEL_IN_MONO, rate).setAudioSource(source);
            if (priorityMic && android.os.Build.VERSION.SDK_INT >= 30) micBuilder.setPrivacySensitive(true);
            candidate = micBuilder.build();
            if (builtin != null) candidate.setPreferredDevice(builtin);
            start(candidate);
            int actualRate = candidate.getSampleRate();
            long numerator = (long) voice.length * actualRate;
            if (actualRate <= 0 || numerator % RollingAudio.RATE != 0)
                throw new IllegalStateException("Unsupported microphone block duration");
            nativeVoice = actualRate == RollingAudio.RATE ? voice : new short[(int)(numerator / RollingAudio.RATE)];
            microphone = candidate;
        } finally {
            if (candidate != null && microphone != candidate) {
                stop(candidate);
                candidate.release();
            }
        }
    }

    private static AudioRecord.Builder builder(int channelMask, int rate) {
        int minimum = AudioRecord.getMinBufferSize(rate, channelMask, AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) throw new IllegalStateException("Unsupported audio format");
        int channelCount = channelMask == AudioFormat.CHANNEL_IN_MONO ? 1 : 2;
        AudioFormat format = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate).setChannelMask(channelMask).build();
        // 240 ms of driver buffering tolerates short scheduling stalls without delaying the first read.
        int bufferBytes = Math.max(minimum, rate * channelCount * 2 * 240 / 1000);
        return new AudioRecord.Builder().setAudioFormat(format).setBufferSizeInBytes(bufferBytes);
    }

    private static void start(AudioRecord recorder) {
        recorder.startRecording();
        if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
            throw new IllegalStateException("Recording did not start");
    }

    boolean read(short[] stereo) {
        if (microphone == null) {
            if (!readFully(playback, stereo)) return false;
            playbackPeak = peak(stereo);
            return !stopped;
        }
        if (!readFully(microphone, nativeVoice)) return false;
        micPeak = peak(nativeVoice);
        converter.convert(nativeVoice, voice);
        boolean hasPlayback = readMixedPlayback(stereo);
        for (int i = 0; i < voice.length; i++) {
            int left = i * 2;
            short mono = voice[i];
            if (!hasPlayback) {
                stereo[left] = mono;
                stereo[left + 1] = mono;
            } else {
                stereo[left] = mix(stereo[left], mono);
                stereo[left + 1] = mix(stereo[left + 1], mono);
            }
        }
        return !stopped;
    }

    private synchronized boolean readMixedPlayback(short[] stereo) {
        playbackPeak = 0;
        if (stopped || playback == null) return false;
        int count;
        try {
            count = playback.read(stereo, 0, stereo.length, AudioRecord.READ_NON_BLOCKING);
        } catch (IllegalStateException disconnected) {
            count = AudioRecord.ERROR_INVALID_OPERATION;
        }
        if (count < 0) {
            Runnable lost = playbackLost;
            detachPlayback();
            lost.run();
            return false;
        }
        java.util.Arrays.fill(stereo, count, stereo.length, (short)0);
        playbackPeak = peak(stereo);
        return true;
    }

    private static int peak(short[] samples) {
        int peak = 0;
        for (short sample : samples) peak = Math.max(peak, Math.abs((int)sample));
        return peak;
    }

    String microphoneRoute() {
        if (microphone == null) return "";
        AudioDeviceInfo device = microphone.getRoutedDevice();
        String label = Lang.text(R.string.system_input);
        if (device != null) {
            switch (device.getType()) {
                case AudioDeviceInfo.TYPE_BUILTIN_MIC: label = Lang.text(R.string.built_in_microphone); break;
                case AudioDeviceInfo.TYPE_WIRED_HEADSET: label = Lang.text(R.string.wired_headset); break;
                case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                case AudioDeviceInfo.TYPE_BLE_HEADSET: label = Lang.text(R.string.bluetooth_headset); break;
                case AudioDeviceInfo.TYPE_USB_DEVICE:
                case AudioDeviceInfo.TYPE_USB_HEADSET: label = Lang.text(R.string.usb_microphone); break;
                default:
                    if (device.getProductName() != null) label = device.getProductName().toString();
            }
            if (preferBuiltin && device.getType() != AudioDeviceInfo.TYPE_BUILTIN_MIC) label += Lang.text(R.string.selected_by_android);
        }
        return String.format(Lang.locale(), Lang.text(R.string.s_s_hz), label, microphone.getSampleRate());
    }

    String microphonePolicyWarning() {
        if (microphone == null) return "";
        if (audioManager.isMicrophoneMute())
            return Lang.text(R.string.android_microphone_access_is_off_enable_it);
        AudioRecordingConfiguration state = microphone.getActiveRecordingConfiguration();
        if (state != null && state.isClientSilenced())
            return Lang.text(R.string.android_has_silenced_vox_ferrum_check_microphone);
        return "";
    }

    private boolean readFully(AudioRecord input, short[] data) {
        int offset = 0;
        while (offset < data.length && !stopped) {
            int n = input.read(data, offset, data.length - offset, AudioRecord.READ_BLOCKING);
            if (n <= 0) {
                if (stopped) return false;
                throw new IllegalStateException("Audio stream disconnected");
            }
            offset += n;
        }
        return !stopped;
    }

    private static short mix(short a, short b) {
        int sum = a + b;
        int level = Math.abs(sum);
        final int knee = 28000, headroom = Short.MAX_VALUE - knee;
        if (level <= knee) return (short) sum;
        int excess = level - knee;
        int limited = knee + headroom * excess / (excess + headroom);
        return (short) (sum < 0 ? -limited : limited);
    }

    /** Called on the main thread to wake a blocked reader. The reader owns release after start. */
    synchronized void stop() {
        if (stopped) return;
        stopped = true;
        stop(playback);
        stop(microphone);
    }

    private static void stop(AudioRecord input) {
        if (input == null) return;
        try { input.stop(); } catch (IllegalStateException ignored) { }
    }

    synchronized void close() {
        stop();
        if (playback != null) { playback.release(); playback = null; }
        if (microphone != null) { microphone.release(); microphone = null; }
    }
}
