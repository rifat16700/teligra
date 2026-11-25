package org.telegram.custom.aicall;

import org.telegram.custom.utils.CustomLogger;
import org.telegram.messenger.ApplicationLoader;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Lock-free background 16-bit PCM mono WAV call recorder.
 * Captures caller and local/AI frames via an async queue so urgent
 * WebRTC audio threads never block on disk I/O.
 */
public final class CallAudioRecorder {
    private static final String TAG = "CallAudioRecorder";
    private static final int DEFAULT_SAMPLE_RATE = 48000;
    private static final int MAX_QUEUE_FRAMES = 400; // ~4s buffer

    private volatile boolean active = false;
    private File outputFile;
    private int sampleRate = DEFAULT_SAMPLE_RATE;
    private long dataBytesWritten = 0L;
    private long startedAtMs = 0L;

    private final LinkedBlockingQueue<byte[]> remoteQueue = new LinkedBlockingQueue<>(MAX_QUEUE_FRAMES);
    private volatile byte[] latestLocalFrame;
    private Thread writerThread;

    public synchronized void start(long callId, int initialSampleRate) {
        if (active) {
            return;
        }
        this.sampleRate = initialSampleRate > 0 ? initialSampleRate : DEFAULT_SAMPLE_RATE;
        this.dataBytesWritten = 0L;
        this.startedAtMs = System.currentTimeMillis();
        this.latestLocalFrame = null;
        this.remoteQueue.clear();

        File dir = new File(ApplicationLoader.getFilesDirFixed(), "ai_call_recordings");
        if (!dir.exists() && !dir.mkdirs()) {
            CustomLogger.e(TAG, "Failed to create recording directory: " + dir.getAbsolutePath());
            return;
        }
        this.outputFile = new File(dir, "call_" + startedAtMs + "_" + callId + ".wav");
        this.active = true;

        writerThread = new Thread(this::runWriterLoop, "AiCallRecorderWriter");
        writerThread.start();
        CustomLogger.i(TAG, "Started call recording: " + outputFile.getAbsolutePath());
    }

    public void onRemotePcm(byte[] pcm, int length, int rate) {
        if (!active || pcm == null || length <= 0) return;
        if (rate > 0 && dataBytesWritten == 0L) {
            this.sampleRate = rate;
        }
        byte[] copy = new byte[length];
        System.arraycopy(pcm, 0, copy, 0, length);
        if (!remoteQueue.offer(copy)) {
            remoteQueue.poll();
            remoteQueue.offer(copy);
        }
    }

    public void onLocalPcm(byte[] pcm, int length, int rate) {
        if (!active || pcm == null || length <= 0) return;
        byte[] copy = new byte[length];
        System.arraycopy(pcm, 0, copy, 0, length);
        latestLocalFrame = copy;
    }

    public synchronized File stopAndFinalize() {
        if (!active) {
            return null;
        }
        active = false;
        if (writerThread != null) {
            writerThread.interrupt();
            try {
                writerThread.join(1500);
            } catch (InterruptedException ignored) {}
            writerThread = null;
        }
        remoteQueue.clear();
        latestLocalFrame = null;

        if (outputFile == null || !outputFile.exists() || dataBytesWritten <= 0L) {
            if (outputFile != null && outputFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                outputFile.delete();
            }
            outputFile = null;
            return null;
        }
        patchWavHeader(outputFile, sampleRate, dataBytesWritten);
        CustomLogger.i(TAG, "Finalized call recording: " + outputFile.getName() + " (" + dataBytesWritten + " PCM bytes)");
        File result = outputFile;
        outputFile = null;
        return result;
    }

    public long getDurationSeconds() {
        if (sampleRate <= 0 || dataBytesWritten <= 0L) return 0L;
        return Math.max(1L, dataBytesWritten / (sampleRate * 2L));
    }

    private void runWriterLoop() {
        BufferedOutputStream out = null;
        try {
            out = new BufferedOutputStream(new FileOutputStream(outputFile), 16384);
            out.write(new byte[44]); // Placeholder WAV header

            while (active || !remoteQueue.isEmpty()) {
                byte[] remoteFrame;
                try {
                    remoteFrame = remoteQueue.poll(100, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    if (!active && remoteQueue.isEmpty()) break;
                    continue;
                }
                if (remoteFrame == null) continue;

                byte[] localFrame = latestLocalFrame;
                if (localFrame != null && localFrame.length >= remoteFrame.length) {
                    mixInPlace(remoteFrame, localFrame, remoteFrame.length);
                }
                out.write(remoteFrame);
                dataBytesWritten += remoteFrame.length;
            }
            out.flush();
        } catch (IOException e) {
            CustomLogger.e(TAG, "Error writing call WAV file", e);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {}
            }
        }
    }

    private static void mixInPlace(byte[] remote, byte[] local, int byteLength) {
        int samples = byteLength / 2;
        for (int i = 0; i < samples; i++) {
            int idx = i * 2;
            short r = (short) ((remote[idx] & 0xFF) | (remote[idx + 1] << 8));
            short l = (short) ((local[idx] & 0xFF) | (local[idx + 1] << 8));
            int mixed = r + l;
            if (mixed > 32767) mixed = 32767;
            else if (mixed < -32768) mixed = -32768;
            remote[idx] = (byte) (mixed & 0xFF);
            remote[idx + 1] = (byte) ((mixed >> 8) & 0xFF);
        }
    }

    private static void patchWavHeader(File file, int rate, long dataLen) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            long totalDataLen = dataLen + 36L;
            long byteRate = rate * 2L;
            byte[] header = new byte[44];
            header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
            writeIntLE(header, 4, (int) totalDataLen);
            header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
            header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
            writeIntLE(header, 16, 16);
            writeShortLE(header, 20, (short) 1); // PCM
            writeShortLE(header, 22, (short) 1); // Mono
            writeIntLE(header, 24, rate);
            writeIntLE(header, 28, (int) byteRate);
            writeShortLE(header, 32, (short) 2); // Block align
            writeShortLE(header, 34, (short) 16); // Bits per sample
            header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
            writeIntLE(header, 40, (int) dataLen);
            raf.seek(0);
            raf.write(header);
        } catch (IOException e) {
            CustomLogger.e(TAG, "Failed to patch WAV header", e);
        }
    }

    private static void writeIntLE(byte[] buf, int offset, int value) {
        buf[offset] = (byte) (value & 0xFF);
        buf[offset + 1] = (byte) ((value >> 8) & 0xFF);
        buf[offset + 2] = (byte) ((value >> 16) & 0xFF);
        buf[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    private static void writeShortLE(byte[] buf, int offset, short value) {
        buf[offset] = (byte) (value & 0xFF);
        buf[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }
}
