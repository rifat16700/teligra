package org.telegram.custom.aicall;

/**
 * Pure-Java 16-bit little-endian PCM audio resampler and channel downmixer.
 * Bridges WebRTC audio rates (typically 48 kHz) with Gemini Live API
 * input (16 kHz) and output (24 kHz).
 */
public final class PcmResampler {

    private PcmResampler() {}

    /**
     * Converts interleaved 16-bit little-endian PCM to mono.
     */
    public static byte[] toMono(byte[] pcm, int length, int channels) {
        if (pcm == null || length <= 0) {
            return new byte[0];
        }
        int safeLen = Math.min(length, pcm.length) & ~1;
        if (channels <= 1) {
            byte[] copy = new byte[safeLen];
            System.arraycopy(pcm, 0, copy, 0, safeLen);
            return copy;
        }
        int frameBytes = channels * 2;
        int frameCount = safeLen / frameBytes;
        byte[] mono = new byte[frameCount * 2];
        for (int f = 0; f < frameCount; f++) {
            int sum = 0;
            int base = f * frameBytes;
            for (int c = 0; c < channels; c++) {
                int idx = base + c * 2;
                short s = (short) ((pcm[idx] & 0xFF) | (pcm[idx + 1] << 8));
                sum += s;
            }
            short avg = (short) (sum / channels);
            mono[f * 2] = (byte) (avg & 0xFF);
            mono[f * 2 + 1] = (byte) ((avg >> 8) & 0xFF);
        }
        return mono;
    }

    /**
     * Resamples 16-bit little-endian mono PCM from srcRate to dstRate.
     * Uses bucket averaging for downsampling and linear interpolation for upsampling.
     */
    public static byte[] resampleMono(byte[] src, int srcLen, int srcRate, int dstRate) {
        if (src == null || srcLen <= 0 || srcRate <= 0 || dstRate <= 0) {
            return new byte[0];
        }
        int safeBytes = Math.min(srcLen, src.length) & ~1;
        if (srcRate == dstRate) {
            byte[] copy = new byte[safeBytes];
            System.arraycopy(src, 0, copy, 0, safeBytes);
            return copy;
        }
        int inSamples = safeBytes / 2;
        if (inSamples == 0) {
            return new byte[0];
        }
        int outSamples = Math.max(1, (int) Math.round(inSamples * (dstRate / (double) srcRate)));
        byte[] out = new byte[outSamples * 2];

        if (srcRate > dstRate) {
            // Downsample with bucket averaging (e.g. 48000 -> 16000)
            double ratio = srcRate / (double) dstRate;
            for (int i = 0; i < outSamples; i++) {
                int start = (int) Math.floor(i * ratio);
                int end = (int) Math.floor((i + 1) * ratio);
                if (end <= start) end = start + 1;
                if (end > inSamples) end = inSamples;
                long accum = 0;
                int count = 0;
                for (int j = start; j < end; j++) {
                    int idx = j * 2;
                    short s = (short) ((src[idx] & 0xFF) | (src[idx + 1] << 8));
                    accum += s;
                    count++;
                }
                short sample = count > 0 ? (short) (accum / count) : 0;
                out[i * 2] = (byte) (sample & 0xFF);
                out[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
            }
        } else {
            // Upsample with linear interpolation (e.g. 24000 -> 48000)
            double step = (inSamples - 1) / (double) Math.max(1, outSamples - 1);
            if (inSamples == 1) step = 0.0;
            for (int i = 0; i < outSamples; i++) {
                double pos = i * step;
                int idx0 = (int) Math.floor(pos);
                int idx1 = Math.min(inSamples - 1, idx0 + 1);
                double frac = pos - idx0;

                short s0 = (short) ((src[idx0 * 2] & 0xFF) | (src[idx0 * 2 + 1] << 8));
                short s1 = (short) ((src[idx1 * 2] & 0xFF) | (src[idx1 * 2 + 1] << 8));
                short sample = (short) Math.round(s0 + (s1 - s0) * frac);

                out[i * 2] = (byte) (sample & 0xFF);
                out[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
            }
        }
        return out;
    }
}
