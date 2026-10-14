package com.EonCuber.audio.platforms;

import com.EonCuber.audio.AudioBuffer;
import com.EonCuber.audio.SystemAudioStreamDevice;
import com.EonCuber.audio.channelConfiguration;
import com.EonCuber.config.Settings;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Shared base for every native system-audio capture device.
 * <p>
 * Threading: the getters read one immutable {@link StreamState} snapshot through a volatile reference and never
 * lock, so the UI / QFT / Render threads can poll them every frame. {@link #activate()} / {@link #deactivate()}
 * serialize on the owning platform's lock. The {@code deliver*} methods are called from the platform's native
 * audio thread and never throw.
 */
abstract class NativeCaptureDevice implements SystemAudioStreamDevice {

    // speaker positions, as reported by the native layer
    enum Speaker { MONO, FL, FR, FC, LFE, BL, BR, SL, SR, FLC, FRC, BC, TC, TFL, TFC, TFR, TBL, TBC, TBR, OTHER }

    enum SampleFormat {
        F32(4), F64(8), S16(2), S24_PACKED(3), S32(4), U8(1);
        final int bytes;
        SampleFormat(int bytes){ this.bytes = bytes; }
    }

    /** What the native stream actually delivers. */
    record NativeFormat(int sampleRate, SampleFormat sampleFormat, ByteOrder order, Speaker[] speakers) {
        int channels(){ return speakers.length; }
        int frameBytes(){ return speakers.length * sampleFormat.bytes; }
        boolean sameAs(NativeFormat o){
            return o != null && sampleRate == o.sampleRate && sampleFormat == o.sampleFormat
                    && order.equals(o.order) && Arrays.equals(speakers, o.speakers);
        }
    }

    /**
     * How native channels map onto the {@link channelConfiguration} slots.
     * Either a pure permutation (1:1 capture) or a downmix matrix [slot][nativeChannel].
     */
    static final class ChannelMix {
        final channelConfiguration config;
        final int slots;
        final int[] permutation; // slot -> native channel, null when matrix is used
        final float[][] matrix;  // [slot][native channel], null when permutation is used

        private ChannelMix(channelConfiguration config, int[] permutation, float[][] matrix){
            this.config = config;
            this.permutation = permutation;
            this.matrix = matrix;
            this.slots = permutation != null ? permutation.length : matrix.length;
        }
    }

    /** Immutable snapshot of everything consumers read. */
    record StreamState(String name, NativeFormat format, ChannelMix mix, AudioBuffer[] buffers, int ringLength) {}

    // FiveOne slot order: [BL,FL,C,FR,BR,Sub]
    private static final int S_BL = 0, S_FL = 1, S_C = 2, S_FR = 3, S_BR = 4, S_SUB = 5;
    private static final float HALF_POWER = (float) Math.sqrt(0.5);
    // default WAVE channel order, used when a device reports no positions
    private static final Speaker[] WAVE_ORDER = {
            Speaker.FL, Speaker.FR, Speaker.FC, Speaker.LFE, Speaker.BL, Speaker.BR, Speaker.FLC, Speaker.FRC,
            Speaker.BC, Speaker.SL, Speaker.SR, Speaker.TC, Speaker.TFL, Speaker.TFC, Speaker.TFR, Speaker.TBL,
            Speaker.TBC, Speaker.TBR
    };

    private final Object lifecycleLock;
    private final Object stateLock = new Object();
    private volatile StreamState state;
    private volatile boolean active;

    // scratch space, only touched by the delivering thread (guarded by deliveryLock for safety across rebinds)
    private final Object deliveryLock = new Object();
    private float[][] nativeScratch = new float[0][0];
    private float[][] mixScratch = new float[0][0];
    private float[] zeros = new float[0];
    private volatile long lastDeliveryErrorNanos;

    NativeCaptureDevice(Object lifecycleLock, String name, NativeFormat format){
        this.lifecycleLock = lifecycleLock;
        publish(name, format);
    }

    // ---------------------------------------------------------------- SystemAudioStreamDevice

    @Override public channelConfiguration channelConfig(){ return state.mix().config; }
    @Override public AudioBuffer[] activeBuffers(){ return state.buffers(); }
    @Override public int sampleRate(){ return state.format().sampleRate(); }
    @Override public boolean isActive(){ return active; }
    @Override public String deviceName(){ return state.name(); }

    @Override
    public final void activate(){
        synchronized (lifecycleLock) {
            if (active) return;
            active = true;
            try {
                openNative();
            } catch (RuntimeException | Error e) {
                active = false;
                if (e instanceof IllegalStateException ise) throw ise;
                throw new IllegalStateException("Could not start capturing '" + deviceName() + "'", e);
            }
        }
    }

    @Override
    public final void deactivate(){
        synchronized (lifecycleLock) {
            if (!active) return;
            active = false;
            try {
                closeNative();
            } catch (RuntimeException e) {
                log("Error while stopping '" + deviceName() + "'", e);
            }
        }
    }

    /** Starts native capture. Called with the platform lock held; must throw if the stream cannot be opened. */
    protected abstract void openNative();

    /** Stops native capture and releases native resources. Called with the platform lock held. */
    protected abstract void closeNative();

    /** Called by native threads when capture died on its own (device removed, server gone, ...). */
    protected final void markInactive(String reason){
        if (active) log("Capture of '" + deviceName() + "' stopped: " + reason, null);
        active = false;
    }

    // ---------------------------------------------------------------- snapshot management

    final StreamState state(){ return state; }

    /**
     * Publishes a new snapshot. Buffers are reused only when rate, slot count and ring length are all unchanged,
     * otherwise new (empty) buffers are created.
     */
    final void publish(String name, NativeFormat format){
        ChannelMix mix = layoutFor(format.speakers());
        int ring = ringLength(format.sampleRate());
        synchronized (stateLock) {
            StreamState old = state;
            AudioBuffer[] buffers;
            if (old != null && old.format().sampleRate() == format.sampleRate()
                    && old.mix().slots == mix.slots && old.ringLength() == ring) {
                buffers = old.buffers();
            } else {
                buffers = newBuffers(mix.slots, ring);
            }
            state = new StreamState(name, format, mix, buffers, ring);
        }
    }

    /** Re-reads the ring length from {@link Settings} and swaps in new, empty buffers if it changed. */
    final void settingsUpdated(){
        synchronized (stateLock) {
            StreamState old = state;
            int ring = ringLength(old.format().sampleRate());
            if (ring == old.ringLength()) return;
            state = new StreamState(old.name(), old.format(), old.mix(), newBuffers(old.mix().slots, ring), ring);
        }
    }

    private static AudioBuffer[] newBuffers(int count, int ring){
        AudioBuffer[] out = new AudioBuffer[count];
        for (int i = 0; i < count; i++) out[i] = new AudioBuffer(ring);
        return out;
    }

    /** N = ceil(frequencyCyclesPerFrequencySample * (sampleRate / frequencyDisplayRange[0])) */
    static int ringLength(int sampleRate){
        double cycles = Settings.getFrequencyCyclesPerFrequencySample();
        double lowestFrequency = Settings.getFrequencyDisplayRange()[0];
        if (!(lowestFrequency > 0))
            throw new IllegalStateException("frequencyDisplayRange[0] must be > 0 Hz to size the audio buffers, got " + lowestFrequency);
        double n = Math.ceil(cycles * ((double) sampleRate / lowestFrequency));
        if (!(n >= 1) || n > Integer.MAX_VALUE)
            throw new IllegalStateException("Invalid audio buffer length " + n + " (frequencyCyclesPerFrequencySample="
                    + cycles + ", sampleRate=" + sampleRate + ", frequencyDisplayRange[0]=" + lowestFrequency + ")");
        return (int) n;
    }

    /** Capture period target: about 3 ms, as a power of two (128 frames at 44.1/48 kHz, 256 at 96 kHz). */
    static int targetPeriodFrames(int sampleRate){
        return Math.max(32, Integer.highestOneBit(Math.max(1, sampleRate * 3 / 1000)));
    }

    // ---------------------------------------------------------------- layout

    static Speaker[] defaultOrder(int channels){
        if (channels == 1) return new Speaker[]{Speaker.MONO};
        Speaker[] out = new Speaker[channels];
        for (int i = 0; i < channels; i++) out[i] = i < WAVE_ORDER.length ? WAVE_ORDER[i] : Speaker.OTHER;
        return out;
    }

    static ChannelMix layoutFor(Speaker[] speakers){
        int n = speakers.length;
        if (n == 0) throw new IllegalArgumentException("device has no channels");
        if (n == 1) return new ChannelMix(channelConfiguration.Mono, new int[]{0}, null);

        boolean allUnknown = true;
        for (Speaker s : speakers) if (s != Speaker.OTHER) { allUnknown = false; break; }
        if (allUnknown) speakers = defaultOrder(n);

        if (n == 2) {
            int fl = indexOf(speakers, Speaker.FL), fr = indexOf(speakers, Speaker.FR);
            if (fl >= 0 && fr >= 0) return new ChannelMix(channelConfiguration.Stereo, new int[]{fl, fr}, null);
        }
        if (n == 6) {
            int fl = indexOf(speakers, Speaker.FL), fr = indexOf(speakers, Speaker.FR);
            int fc = indexOf(speakers, Speaker.FC), lfe = indexOf(speakers, Speaker.LFE);
            int bl = indexOf(speakers, Speaker.BL), br = indexOf(speakers, Speaker.BR);
            if (bl < 0 && br < 0) { bl = indexOf(speakers, Speaker.SL); br = indexOf(speakers, Speaker.SR); }
            if (fl >= 0 && fr >= 0 && fc >= 0 && lfe >= 0 && bl >= 0 && br >= 0)
                return new ChannelMix(channelConfiguration.FiveOne, new int[]{bl, fl, fc, fr, br, lfe}, null);
        }

        // everything else: downmix to 5.1
        float[][] base = new float[6][n];
        for (int i = 0; i < n; i++) {
            switch (speakers[i]) {
                case FL, FLC, TFL -> base[S_FL][i] = 1f;
                case FR, FRC, TFR -> base[S_FR][i] = 1f;
                case FC, TFC, TC, MONO -> base[S_C][i] = 1f;
                case LFE -> base[S_SUB][i] = 1f;
                case BL, SL, TBL -> base[S_BL][i] = 1f;
                case BR, SR, TBR -> base[S_BR][i] = 1f;
                case BC, TBC -> { base[S_BL][i] = HALF_POWER; base[S_BR][i] = HALF_POWER; }
                case OTHER -> { }
            }
        }
        // k channels feeding one slot are each scaled by 1/sqrt(k)
        for (int slot = 0; slot < 6; slot++) {
            int k = 0;
            for (int i = 0; i < n; i++) if (base[slot][i] != 0f) k++;
            if (k > 1) {
                float scale = (float) (1.0 / Math.sqrt(k));
                for (int i = 0; i < n; i++) base[slot][i] *= scale;
            }
        }
        return new ChannelMix(channelConfiguration.FiveOne, null, base);
    }

    private static int indexOf(Speaker[] speakers, Speaker s){
        for (int i = 0; i < speakers.length; i++) if (speakers[i] == s) return i;
        return -1;
    }

    // ---------------------------------------------------------------- delivery (native threads)

    /** Interleaved frames in the current snapshot's native format. */
    final void deliverInterleaved(MemorySegment data, int frames){
        if (frames <= 0) return;
        try {
            StreamState s = state;
            NativeFormat f = s.format();
            int ch = f.channels();
            synchronized (deliveryLock) {
                float[][] nat = nativeScratch(ch, frames);
                SampleFormat sf = f.sampleFormat();
                if (sf == SampleFormat.F32) {
                    ValueLayout.OfFloat layout = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(f.order());
                    for (int c = 0; c < ch; c++) {
                        float[] dst = nat[c];
                        long off = (long) c * 4;
                        long stride = (long) ch * 4;
                        for (int i = 0; i < frames; i++, off += stride) dst[i] = data.get(layout, off);
                    }
                } else {
                    int bytes = sf.bytes;
                    long stride = (long) ch * bytes;
                    for (int c = 0; c < ch; c++) {
                        float[] dst = nat[c];
                        long off = (long) c * bytes;
                        for (int i = 0; i < frames; i++, off += stride) dst[i] = readSample(data, off, sf, f.order());
                    }
                }
                emit(s, nat, frames);
            }
        } catch (Throwable t) {
            deliveryError("Dropped audio packet", t);
        }
    }

    /** One segment per native channel (non-interleaved). */
    final void deliverPlanar(MemorySegment[] planes, int frames){
        if (frames <= 0) return;
        try {
            StreamState s = state;
            NativeFormat f = s.format();
            int ch = Math.min(f.channels(), planes.length);
            synchronized (deliveryLock) {
                float[][] nat = nativeScratch(f.channels(), frames);
                SampleFormat sf = f.sampleFormat();
                for (int c = 0; c < f.channels(); c++) {
                    float[] dst = nat[c];
                    if (c >= ch) { Arrays.fill(dst, 0, frames, 0f); continue; }
                    MemorySegment plane = planes[c];
                    if (sf == SampleFormat.F32) {
                        MemorySegment.copy(plane, ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(f.order()), 0, dst, 0, frames);
                    } else {
                        long off = 0;
                        for (int i = 0; i < frames; i++, off += sf.bytes) dst[i] = readSample(plane, off, sf, f.order());
                    }
                }
                emit(s, nat, frames);
            }
        } catch (Throwable t) {
            deliveryError("Dropped audio packet", t);
        }
    }

    /** Pushes {@code frames} of digital silence into every channel. */
    final void deliverSilence(long frames){
        if (frames <= 0) return;
        try {
            StreamState s = state;
            int count = (int) Math.min(frames, s.ringLength());
            synchronized (deliveryLock) {
                if (zeros.length != count) zeros = new float[count];
                for (AudioBuffer b : s.buffers()) b.addSamples(zeros);
            }
        } catch (Throwable t) {
            deliveryError("Dropped silence", t);
        }
    }

    private void emit(StreamState s, float[][] nat, int frames){
        ChannelMix mix = s.mix();
        AudioBuffer[] buffers = s.buffers();
        if (mix.permutation != null) {
            // 1:1 capture, channels only reordered; all channels written back to back
            for (int slot = 0; slot < mix.slots; slot++) buffers[slot].addSamples(nat[mix.permutation[slot]]);
            return;
        }
        float[][] out = mixScratch(mix.slots, frames);
        for (int slot = 0; slot < mix.slots; slot++) {
            float[] dst = out[slot];
            Arrays.fill(dst, 0f);
            float[] row = mix.matrix[slot];
            for (int c = 0; c < row.length; c++) {
                float w = row[c];
                if (w == 0f) continue;
                float[] src = nat[c];
                for (int i = 0; i < frames; i++) dst[i] += w * src[i];
            }
        }
        for (int slot = 0; slot < mix.slots; slot++) buffers[slot].addSamples(out[slot]);
    }

    private float[][] nativeScratch(int channels, int frames){
        if (nativeScratch.length != channels || (channels > 0 && nativeScratch[0].length != frames))
            nativeScratch = new float[channels][frames];
        return nativeScratch;
    }

    private float[][] mixScratch(int slots, int frames){
        if (mixScratch.length != slots || (slots > 0 && mixScratch[0].length != frames))
            mixScratch = new float[slots][frames];
        return mixScratch;
    }

    private static float readSample(MemorySegment seg, long off, SampleFormat sf, ByteOrder order){
        boolean le = order == ByteOrder.LITTLE_ENDIAN;
        switch (sf) {
            case F32: return seg.get(ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(order), off);
            case F64: return (float) seg.get(ValueLayout.JAVA_DOUBLE_UNALIGNED.withOrder(order), off);
            case S16: return seg.get(ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(order), off) / 32768f;
            case S32: return (float) (seg.get(ValueLayout.JAVA_INT_UNALIGNED.withOrder(order), off) / 2147483648.0);
            case U8: return ((seg.get(ValueLayout.JAVA_BYTE, off) & 0xFF) - 128) / 128f;
            case S24_PACKED: {
                int b0 = seg.get(ValueLayout.JAVA_BYTE, off) & 0xFF;
                int b1 = seg.get(ValueLayout.JAVA_BYTE, off + 1) & 0xFF;
                int b2 = seg.get(ValueLayout.JAVA_BYTE, off + 2) & 0xFF;
                int v = le ? (b0 | (b1 << 8) | (b2 << 16)) : (b2 | (b1 << 8) | (b0 << 16));
                v = (v << 8) >> 8; // sign extend
                return v / 8388608f;
            }
            default: return 0f;
        }
    }

    // ---------------------------------------------------------------- misc

    /** Delivery runs every few ms: report failures at most once per second per device. */
    private void deliveryError(String what, Throwable t){
        long now = System.nanoTime();
        if (now - lastDeliveryErrorNanos < 1_000_000_000L) return;
        lastDeliveryErrorNanos = now;
        log(what + " for '" + safeName() + "'", t);
    }

    private String safeName(){
        StreamState s = state;
        return s == null ? "?" : s.name();
    }

    static void log(String message, Throwable t){
        System.err.println("[Avis audio] " + message + (t == null ? "" : ": " + t));
    }
}
