package dev.voxferrum;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** A single writer appends stereo frames. A snapshot retains immutable byte ranges for export. */
final class RollingAudio implements Closeable {
    static final int RATE = 48_000;
    static final int CHANNELS = 2;
    static final int BLOCK_SAMPLES = RATE * CHANNELS / 25;
    static final int MAX_SECONDS = 15 * 60;
    private static final int RAM_SECONDS = 120;
    private static final int BYTES_PER_SECOND = RATE * CHANNELS * Short.BYTES;
    private final ArrayDeque<Segment> segments = new ArrayDeque<>();
    private final File cache;
    private final byte[] input = new byte[BLOCK_SAMPLES * 2];
    private int totalBytes;
    private long writtenBytes;
    private final ArrayDeque<Long> marks = new ArrayDeque<>();

    synchronized boolean mark() {
        if (closed || writtenBytes == 0) return false;
        // Collapse accidental double taps on the same moment.
        if (marks.isEmpty() || writtenBytes - marks.peekLast() >= BYTES_PER_SECOND / 4)
            marks.addLast(writtenBytes);
        return true;
    }
    private boolean closed;

    RollingAudio(File cache) { this.cache = cache; }

    synchronized void append(short[] block, int samples, int windowSeconds) throws IOException {
        if (closed) return;
        for (int i = 0; i < samples; i++) {
            input[2*i] = (byte) block[i];
            input[2*i+1] = (byte) (block[i] >> 8);
        }
        int size = samples * 2;
        int offset = 0;
        boolean onDisk = windowSeconds > RAM_SECONDS;
        while (offset < size) {
            Segment tail = segments.peekLast();
            if (tail == null || tail.used == tail.capacity) {
                tail = new Segment(cache, onDisk);
                segments.addLast(tail);
            }
            int length = Math.min(size - offset, tail.capacity - tail.used);
            tail.append(input, offset, length);
            totalBytes += length;
            offset += length;
        }
        writtenBytes += size;
        // Short presets keep the original two-minute in-memory history.
        int limit = Math.max(RAM_SECONDS, windowSeconds) * BYTES_PER_SECOND;
        while (totalBytes - segments.getFirst().used >= limit) {
            Segment expired = segments.removeFirst();
            totalBytes -= expired.used;
            expired.release();
        }
        while (!marks.isEmpty() && marks.peekFirst() < writtenBytes - totalBytes) marks.removeFirst();
    }

    synchronized int milliseconds() {
        return Math.min(totalBytes, MAX_SECONDS * BYTES_PER_SECOND) / (BYTES_PER_SECOND / 1000);
    }

    synchronized Snapshot last(int seconds) {
        int wanted = Math.min(totalBytes, seconds * BYTES_PER_SECOND);
        List<Slice> slices = new ArrayList<>();
        int skip = totalBytes - wanted;
        for (Segment segment : segments) {
            if (skip >= segment.used) {
                skip -= segment.used;
                continue;
            }
            segment.retain();
            slices.add(new Slice(segment, skip, segment.used - skip));
            skip = 0;
        }
        long start = writtenBytes - wanted;
        ArrayList<Integer> positions = new ArrayList<>();
        for (long at : marks) {
            if (at >= start) positions.add((int)((at - start) * 1000 / BYTES_PER_SECOND));
        }
        return new Snapshot(slices, wanted / 2, positions);
    }

    @Override public synchronized void close() {
        closed = true;
        while (!segments.isEmpty()) segments.removeFirst().release();
        totalBytes = 0;
    }

    private static final class Segment {
        final byte[] memory;
        final RandomAccessFile file;
        final int capacity;
        final File path;
        int used;
        private int references = 1;

        Segment(File cache, boolean onDisk) throws IOException {
            capacity = (onDisk ? 10 : 1) * BYTES_PER_SECOND;
            if (onDisk) {
                memory = null;
                File temporary = File.createTempFile("vox-replay-", ".pcm", cache);
                try {
                    file = new RandomAccessFile(temporary, "rw");
                } catch (IOException e) {
                    temporary.delete();
                    throw e;
                }
                // On Android the open inode survives unlink, and even a process kill reclaims it.
                path = temporary.delete() ? null : temporary;
            } else {
                memory = new byte[capacity];
                file = null;
                path = null;
            }
        }

        void append(byte[] bytes, int offset, int length) throws IOException {
            if (file == null) System.arraycopy(bytes, offset, memory, used, length);
            else file.write(bytes, offset, length);
            used += length;
        }

        void read(int offset, byte[] bytes, int length) throws IOException {
            if (file == null) {
                System.arraycopy(memory, offset, bytes, 0, length);
            } else {
                ByteBuffer dst = ByteBuffer.wrap(bytes, 0, length);
                while (dst.hasRemaining()) {
                    int got = file.getChannel().read(dst, offset + dst.position());
                    if (got <= 0) throw new IOException("Incomplete recording segment");
                }
            }
        }

        synchronized void retain() { references++; }

        synchronized void release() {
            if (--references != 0 || file == null) return;
            try {
                file.close();
            } catch (IOException ignored) {
                // A finished or discarded segment is no longer writable.
            } finally {
                if (path != null) path.delete();
            }
        }
    }

    private static final class Slice {
        final Segment segment;
        final int offset, length;
        Slice(Segment segment, int offset, int length) {
            this.segment = segment;
            this.offset = offset;
            this.length = length;
        }
    }

    static final class Snapshot implements Closeable {
        private final List<Slice> slices;
        final int samples;

        final List<Integer> marks;
        Snapshot(List<Slice> slices, int samples, List<Integer> marks) {
            this.marks = marks;
            this.slices = slices;
            this.samples = samples;
        }

        /** The optional table maps each unsigned 16-bit sample encoding to its amplified value. */
        void writePcm(OutputStream out, short[] gainTable) throws IOException {
            byte[] chunk = new byte[64 * 1024];
            for (Slice slice : slices) {
                for (int done = 0; done < slice.length;) {
                    int size = Math.min(chunk.length, slice.length - done);
                    slice.segment.read(slice.offset + done, chunk, size);
                    if (gainTable != null) {
                        for (int i = 0; i < size; i += 2) {
                            int original = (chunk[i] & 255) | ((chunk[i + 1] & 255) << 8);
                            short adjusted = gainTable[original];
                            chunk[i] = (byte) adjusted;
                            chunk[i + 1] = (byte) (adjusted >> 8);
                        }
                    }
                    out.write(chunk, 0, size);
                    done += size;
                }
            }
        }

        @Override public void close() {
            for (Slice slice : slices) slice.segment.release();
            slices.clear();
        }
    }
}
