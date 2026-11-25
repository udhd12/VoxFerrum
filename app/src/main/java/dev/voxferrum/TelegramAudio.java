package dev.voxferrum;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.SystemClock;

import java.io.DataInputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.IntConsumer;

/** Converts the archive's PCM16 WAV files to AAC-LC inside a seekable M4A container. */
final class TelegramAudio {
    private static final int RATE = 48000;
    private static final int CHANNELS = 2;
    private static final int FRAME_BYTES = CHANNELS * 2;

    private TelegramAudio() {}

    // The archive writes a fixed RIFF/fmt/data header, including in version 1.0.
    static long readHeader(DataInputStream input) throws IOException {
        byte[] bytes = new byte[44];
        input.readFully(bytes);
        ByteBuffer h = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (h.getInt(0) != 0x46464952 || h.getInt(8) != 0x45564157
                || h.getInt(12) != 0x20746d66 || h.getInt(16) != 16 || h.getShort(20) != 1
                || h.getShort(22) != CHANNELS || h.getInt(24) != RATE || h.getShort(34) != 16
                || h.getInt(36) != 0x61746164) {
            throw new IOException("Unsupported WAV header");
        }
        long size = Integer.toUnsignedLong(h.getInt(40));
        if (size == 0 || size % FRAME_BYTES != 0) throw new IOException("Invalid PCM length");
        return size;
    }

    static void encode(DataInputStream pcm, long pcmBytes, FileDescriptor output,
                       IntConsumer progress) throws IOException {
        MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, CHANNELS);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 192000);
        MediaCodec encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            MediaMuxer muxer = new MediaMuxer(output, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            try {
                writeSamples(pcm, pcmBytes, encoder, muxer, progress);
                muxer.stop();
            } finally {
                muxer.release();
            }
        } finally {
            encoder.release();
        }
    }

    private static void writeSamples(DataInputStream pcm, long pcmBytes, MediaCodec encoder,
                                     MediaMuxer muxer, IntConsumer progress) throws IOException {
        byte[] chunk = new byte[16384];
        long consumed = 0;
        int lastPercent = -1;
        boolean inputEnded = false;
        int track = -1;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long lastProgress = SystemClock.elapsedRealtime();
        while (true) {
            boolean fed = false;
            int slot = inputEnded ? -1 : encoder.dequeueInputBuffer(0);
            if (slot >= 0) {
                ByteBuffer input = encoder.getInputBuffer(slot);
                if (input == null) throw new IOException("Missing AAC input buffer");
                input.clear();
                int capacity = Math.min(chunk.length, input.remaining()) / FRAME_BYTES * FRAME_BYTES;
                if (capacity == 0) throw new IOException("AAC input buffer too small");
                int size = (int) Math.min(pcmBytes - consumed, capacity);
                inputEnded = size == 0;
                if (!inputEnded) {
                    pcm.readFully(chunk, 0, size);
                    input.put(chunk, 0, size);
                }
                long timestampUs = consumed / FRAME_BYTES * 1000000L / RATE;
                encoder.queueInputBuffer(slot, 0, size, timestampUs,
                        inputEnded ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                consumed += size;
                int percent = (int) (consumed * 100 / pcmBytes);
                if (percent != lastPercent) {
                    progress.accept(percent);
                    lastPercent = percent;
                }
                fed = true;
            }
            int frame = encoder.dequeueOutputBuffer(info, fed ? 0 : 5000);
            if (frame == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track = muxer.addTrack(encoder.getOutputFormat());
                muxer.start();
            } else if (frame >= 0) {
                try {
                    if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        ByteBuffer data = encoder.getOutputBuffer(frame);
                        if (data == null || track < 0) throw new IOException("Missing AAC output");
                        muxer.writeSampleData(track, data, info);
                    }
                } finally {
                    encoder.releaseOutputBuffer(frame, false);
                }
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
            }
            if (fed || frame >= 0 || frame == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                lastProgress = SystemClock.elapsedRealtime();
            } else if (SystemClock.elapsedRealtime() - lastProgress > 30000) {
                throw new IOException("AAC encoder timed out");
            }
        }
    }
}
