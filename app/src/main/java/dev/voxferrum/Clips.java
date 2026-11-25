package dev.voxferrum;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.DataInputStream;
import android.os.ParcelFileDescriptor;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class Clips {
    private static final String DIRECTORY = Environment.DIRECTORY_MUSIC + "/VoxFerrum/";
    private static final Uri COLLECTION = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);

    private Clips() {}

    public static final class Clip {
        public final Uri uri;
        public final String name;
        public final long dateMs, bytes;
        public final int durationMs;

        Clip(Uri uri, String name, long dateMs, long bytes) {
            this.uri = uri;
            this.name = name;
            this.dateMs = dateMs;
            this.bytes = bytes;
            this.durationMs = (int) (Math.max(0, bytes - 44) * 1000 / (RollingAudio.RATE * RollingAudio.CHANNELS * 2));
        }
    }

    public static List<Clip> list(Context context) {
        String[] columns = {"_id", "_display_name", "date_added", "_size"};
        ArrayList<Clip> result = new ArrayList<>();
        try (Cursor cursor = context.getContentResolver().query(COLLECTION, columns,
                "relative_path=? AND is_pending=0 AND _display_name LIKE ?",
                new String[]{DIRECTORY, "Vox-%.wav"}, "date_added DESC, _id DESC")) {
            while (cursor != null && cursor.moveToNext()) {
                result.add(new Clip(ContentUris.withAppendedId(COLLECTION, cursor.getLong(0)),
                        cursor.getString(1), cursor.getLong(2) * 1000, cursor.getLong(3)));
            }
        }
        return result;
    }

    static void write(Context context, RollingAudio.Snapshot clip, int gainDb) throws IOException {
        create(context, "", clip.samples * 2, clip.marks, out -> {
            short[] gainTable = null;
            if (gainDb != 0) {
                double multiplier = Math.pow(10, gainDb / 20.0);
                gainTable = new short[65536];
                for (int i=0; i<gainTable.length; i++) gainTable[i] = amplify((short)i, multiplier);
            }
            clip.writePcm(out, gainTable);
        });
    }

    static Clip extract(Context context, Clip source, int startMs, int endMs) throws IOException {
        try (ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(source.uri,"r")) {
            if (descriptor == null) throw new IOException("Файл недоступен");
            try (FileInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
                long pcmBytes = TelegramAudio.readHeader(new DataInputStream(input));
                int frameBytes = RollingAudio.CHANNELS * 2;
                long start = (long) startMs * RollingAudio.RATE / 1000 * frameBytes;
                long end = endMs == source.durationMs ? pcmBytes : (long) endMs * RollingAudio.RATE / 1000 * frameBytes;
                if (start < 0 || end > pcmBytes || end <= start) throw new IOException("Неверные границы");
                input.getChannel().position(44 + start);
                int bytes = Math.toIntExact(end - start);
                List<Integer> marks = new ArrayList<>();
                for (int at : ClipNotes.marks(context,source.uri)) {
                    if (at >= startMs && at <= endMs) marks.add(at-startMs);
                }
                return create(context, "-cut", bytes, marks, out -> {
                    byte[] block = new byte[65536];
                    for (int remaining=bytes; remaining>0;) {
                        int n=input.read(block,0,Math.min(block.length,remaining));
                        if(n<=0) throw new IOException("Неполный WAV");
                        out.write(block,0,n);
                        remaining-=n;
                    }
                });
            }
        }
    }

    private interface PcmWriter { void write(OutputStream stream) throws IOException; }

    private static Clip create(Context context, String suffix, int bytes, List<Integer> marks, PcmWriter writer) throws IOException {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(new Date());
        ContentValues values = new ContentValues();
        String name = "Vox-" + stamp + suffix + ".wav";
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, DIRECTORY);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        ContentResolver resolver = context.getContentResolver();
        Uri uri = resolver.insert(COLLECTION, values);
        if (uri == null) throw new IOException("Не удалось создать файл");
        boolean published = false;
        try {
            OutputStream raw = resolver.openOutputStream(uri, "w");
            if (raw == null) throw new IOException("Файл недоступен");
            try (OutputStream out = new BufferedOutputStream(raw)) {
                writeHeader(out, bytes);
                writer.write(out);
            }
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (resolver.update(uri, values, null, null) != 1) throw new IOException("Файл не опубликован");
            published = true;
            ClipNotes.store(context, uri, marks);
            return new Clip(uri, name, System.currentTimeMillis(), bytes+44L);
        } finally {
            if (!published) resolver.delete(uri, null, null);
        }
    }

    private static void writeHeader(OutputStream out, int bytes) throws IOException {
        int frameBytes = RollingAudio.CHANNELS * 2;
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + bytes);
        header.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
        header.putShort((short) 1).putShort((short) RollingAudio.CHANNELS).putInt(RollingAudio.RATE);
        header.putInt(RollingAudio.RATE * frameBytes).putShort((short) frameBytes).putShort((short) 16);
        header.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(bytes);
        out.write(header.array());
    }

    private static short amplify(short source, double multiplier) {
        double magnitude = Math.abs((double) source) * multiplier;
        // Soft knee above 90% of full scale, leaving headroom instead of flat-topping loud peaks.
        double knee = 0.9 * Short.MAX_VALUE;
        if (magnitude > knee) {
            double excess = magnitude - knee;
            magnitude = knee + excess / (1.0 + excess / (Short.MAX_VALUE - knee));
        }
        return (short) Math.round(Math.copySign(magnitude, source));
    }
}
