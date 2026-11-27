package org.telegram.custom.aicall;

import java.util.Arrays;

/**
 * Thread-safe circular byte buffer with jitter pre-buffering for 16-bit PCM audio.
 * Gemini Live streams voice chunks much faster than 1x real-time playback, so
 * capacity defaults to 60s mono (30s stereo) and grows dynamically up to 120s
 * mono (60s stereo) to ensure long AI replies never drop or skip middle audio.
 */
public final class PcmRingBuffer {
    public static final int DEFAULT_CAPACITY_BYTES = 5_760_000; // 60s at 48 kHz mono 16-bit (30s stereo)
    public static final int MAX_CAPACITY_BYTES = 11_520_000;    // 120s at 48 kHz mono 16-bit (60s stereo)
    public static final int DEFAULT_PREBUFFER_BYTES = 7_680;    // ~80ms at 48 kHz mono 16-bit

    private byte[] buffer;
    private int capacity;
    private final int prebufferBytes;
    private int readPos = 0;
    private int writePos = 0;
    private int size = 0;
    private boolean priming = true;

    public PcmRingBuffer() {
        this(DEFAULT_CAPACITY_BYTES, DEFAULT_PREBUFFER_BYTES);
    }

    public PcmRingBuffer(int capacityBytes) {
        this(capacityBytes, Math.min(DEFAULT_PREBUFFER_BYTES, capacityBytes / 4));
    }

    public PcmRingBuffer(int capacityBytes, int prebufferBytes) {
        this.capacity = Math.max(3840, capacityBytes);
        this.prebufferBytes = Math.max(0, Math.min(prebufferBytes, this.capacity / 2));
        this.buffer = new byte[this.capacity];
    }

    public synchronized void write(byte[] src, int offset, int length) {
        if (src == null || length <= 0 || offset < 0 || offset + length > src.length) {
            return;
        }
        int srcOffset = offset;
        int remaining = length;

        if (size + remaining > capacity && capacity < MAX_CAPACITY_BYTES) {
            int newCap = Math.min(MAX_CAPACITY_BYTES, Math.max(capacity * 2, size + remaining));
            if (newCap > capacity) {
                growCapacity(newCap);
            }
        }

        if (remaining > capacity) {
            srcOffset += (remaining - capacity);
            remaining = capacity;
        }
        int overflow = (size + remaining) - capacity;
        if (overflow > 0) {
            readPos = (readPos + overflow) % capacity;
            size -= overflow;
        }
        int firstChunk = Math.min(remaining, capacity - writePos);
        System.arraycopy(src, srcOffset, buffer, writePos, firstChunk);
        int secondChunk = remaining - firstChunk;
        if (secondChunk > 0) {
            System.arraycopy(src, srcOffset + firstChunk, buffer, 0, secondChunk);
        }
        writePos = (writePos + remaining) % capacity;
        size += remaining;

        if (priming && size >= prebufferBytes) {
            priming = false;
        }
    }

    private void growCapacity(int newCapacity) {
        byte[] next = new byte[newCapacity];
        if (size > 0) {
            int firstChunk = Math.min(size, capacity - readPos);
            System.arraycopy(buffer, readPos, next, 0, firstChunk);
            int secondChunk = size - firstChunk;
            if (secondChunk > 0) {
                System.arraycopy(buffer, 0, next, firstChunk, secondChunk);
            }
        }
        buffer = next;
        capacity = newCapacity;
        readPos = 0;
        writePos = size;
    }

    public synchronized int read(byte[] dst, int offset, int length) {
        if (dst == null || length <= 0 || offset < 0 || offset + length > dst.length) {
            return 0;
        }
        if (priming) {
            if (size >= prebufferBytes && size > 0) {
                priming = false;
            } else {
                Arrays.fill(dst, offset, offset + length, (byte) 0);
                return 0;
            }
        }
        int toRead = Math.min(length, size);
        if (toRead > 0) {
            int firstChunk = Math.min(toRead, capacity - readPos);
            System.arraycopy(buffer, readPos, dst, offset, firstChunk);
            int secondChunk = toRead - firstChunk;
            if (secondChunk > 0) {
                System.arraycopy(buffer, 0, dst, offset + firstChunk, secondChunk);
            }
            readPos = (readPos + toRead) % capacity;
            size -= toRead;
        }
        if (toRead < length) {
            Arrays.fill(dst, offset + toRead, offset + length, (byte) 0);
        }
        if (size == 0) {
            priming = true;
        }
        return toRead;
    }

    /**
     * Releases any buffered tail audio immediately (e.g. when a short AI turn finishes).
     */
    public synchronized void flushForPlayback() {
        if (size > 0) {
            priming = false;
        }
    }

    public synchronized void clear() {
        readPos = 0;
        writePos = 0;
        size = 0;
        priming = true;
    }

    public synchronized int available() {
        return size;
    }
}
