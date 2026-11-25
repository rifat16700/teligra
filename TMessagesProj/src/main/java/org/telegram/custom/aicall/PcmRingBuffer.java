package org.telegram.custom.aicall;

import java.util.Arrays;

/**
 * Thread-safe circular byte buffer for 16-bit PCM audio frames.
 * Drops oldest unread bytes when full to keep live AI voice latency bounded,
 * and zero-fills any shortfall on underrun.
 */
public final class PcmRingBuffer {
    public static final int DEFAULT_CAPACITY_BYTES = 192_000; // ~2.0s at 48 kHz mono 16-bit

    private final byte[] buffer;
    private final int capacity;
    private int readPos = 0;
    private int writePos = 0;
    private int size = 0;

    public PcmRingBuffer() {
        this(DEFAULT_CAPACITY_BYTES);
    }

    public PcmRingBuffer(int capacityBytes) {
        this.capacity = Math.max(1920, capacityBytes);
        this.buffer = new byte[this.capacity];
    }

    public synchronized void write(byte[] src, int offset, int length) {
        if (src == null || length <= 0 || offset < 0 || offset + length > src.length) {
            return;
        }
        int srcOffset = offset;
        int remaining = length;
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
    }

    public synchronized int read(byte[] dst, int offset, int length) {
        if (dst == null || length <= 0 || offset < 0 || offset + length > dst.length) {
            return 0;
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
        return toRead;
    }

    public synchronized void clear() {
        readPos = 0;
        writePos = 0;
        size = 0;
    }

    public synchronized int available() {
        return size;
    }
}
