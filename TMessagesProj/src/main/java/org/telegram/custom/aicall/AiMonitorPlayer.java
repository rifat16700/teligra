package org.telegram.custom.aicall;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;

import org.telegram.custom.utils.CustomLogger;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Standalone Android AudioTrack monitor for Anonymous Live Listen-In.
 * Plays mixed caller + AI audio on USAGE_MEDIA outside WebRTC's
 * WebRtcAudioTrack so WebRTC AEC never sees or cancels AI's voice.
 */
public final class AiMonitorPlayer {
    private static final String TAG = "AiMonitorPlayer";
    private static final int MAX_QUEUE_FRAMES = 60;

    private volatile boolean running = false;
    private volatile int activeRate = 0;
    private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>(MAX_QUEUE_FRAMES);
    private Thread playerThread;

    public synchronized void start(int sampleRate) {
        int rate = sampleRate > 0 ? sampleRate : 48000;
        if (running && activeRate == rate) {
            return;
        }
        stop();
        this.activeRate = rate;
        this.running = true;
        this.queue.clear();

        playerThread = new Thread(() -> runLoop(rate), "AiMonitorPlayerThread");
        playerThread.start();
        CustomLogger.i(TAG, "Started standalone anonymous monitor player at " + rate + " Hz");
    }

    public void enqueueMixedFrame(byte[] callerPcm, byte[] aiPcm, int aiLen, int length) {
        if (!running || callerPcm == null || length <= 0) return;
        int safeLen = Math.min(length, callerPcm.length) & ~1;
        if (safeLen <= 0) return;

        byte[] frame = new byte[safeLen];
        System.arraycopy(callerPcm, 0, frame, 0, safeLen);
        if (aiPcm != null && aiLen >= safeLen) {
            int samples = safeLen / 2;
            for (int i = 0; i < samples; i++) {
                int idx = i * 2;
                short c = (short) ((frame[idx] & 0xFF) | (frame[idx + 1] << 8));
                short a = (short) ((aiPcm[idx] & 0xFF) | (aiPcm[idx + 1] << 8));
                int mixed = c + (a / 2);
                if (mixed > 32767) mixed = 32767;
                else if (mixed < -32768) mixed = -32768;
                frame[idx] = (byte) (mixed & 0xFF);
                frame[idx + 1] = (byte) ((mixed >> 8) & 0xFF);
            }
        }
        if (!queue.offer(frame)) {
            queue.poll();
            queue.offer(frame);
        }
    }

    public synchronized void stop() {
        running = false;
        activeRate = 0;
        queue.clear();
        if (playerThread != null) {
            playerThread.interrupt();
            try {
                playerThread.join(400);
            } catch (InterruptedException ignored) {}
            playerThread = null;
        }
    }

    private void runLoop(int rate) {
        AudioTrack track = null;
        try {
            int minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int bufSize = Math.max(minBuf, 4096);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setSampleRate(rate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .build())
                        .setBufferSizeInBytes(bufSize)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();
            } else {
                track = new AudioTrack(
                        AudioManager.STREAM_MUSIC,
                        rate,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufSize,
                        AudioTrack.MODE_STREAM
                );
            }
            track.play();

            while (running) {
                byte[] frame;
                try {
                    frame = queue.poll(80, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    if (!running) break;
                    continue;
                }
                if (frame != null && running) {
                    track.write(frame, 0, frame.length);
                }
            }
        } catch (Throwable t) {
            CustomLogger.e(TAG, "Anonymous monitor AudioTrack error", t);
        } finally {
            if (track != null) {
                try {
                    track.stop();
                } catch (Throwable ignored) {}
                try {
                    track.release();
                } catch (Throwable ignored) {}
            }
        }
    }
}
