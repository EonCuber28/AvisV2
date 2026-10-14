package com.EonCuber.audio.platforms;

import com.EonCuber.audio.SystemAudioStreamDevice;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static com.EonCuber.audio.platforms.NativeCaptureDevice.*;
import static com.EonCuber.audio.platforms.PulseBindings.*;
import static java.lang.foreign.ValueLayout.*;

/**
 * System audio capture through libpulse (PulseAudio, or PipeWire's pulse server) using sink monitor sources.
 * <p>
 * Device 0 is the default sink's monitor and follows default-sink changes live. The rest are the other sinks that
 * aren't suspended. Each stream is opened with the sink's own rate, channel count and channel map as float32, so the
 * server neither resamples nor remixes. The fragment size is about 3 ms (see
 * {@link NativeCaptureDevice#targetPeriodFrames}), so delivery stays well inside one 144 Hz frame.
 * <p>
 * Threading: all libpulse calls from Java threads happen under the threaded mainloop's lock. Callbacks run on
 * libpulse's mainloop thread, never take {@link #lock}, and never block.
 */
public class Unix_PulseAudio implements AudioPlatform {
    private static final String DEFAULT_SINK = "@DEFAULT_SINK@";
    private static final long TAG_SYNC = 1, TAG_FOLLOW_DEFAULT = 2, TAG_FOLLOW_INDEX = 3;

    private final Object lock = new Object();
    private final Map<String, PulseMonitorDevice> devices = new LinkedHashMap<>(); // guarded by lock
    private final Map<Long, PulseMonitorDevice> byId = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong(1);
    private volatile Session session; // written under lock

    public Unix_PulseAudio(){}

    @Override
    public SystemAudioStreamDevice[] availableDevices(){
        synchronized (lock) {
            Session s = session();
            SinkInfo def;
            List<SinkInfo> sinks;
            s.lock();
            try {
                def = s.querySink(DEFAULT_SINK);
                sinks = s.querySinks();
            } finally {
                s.unlock();
            }
            List<SystemAudioStreamDevice> out = new ArrayList<>();
            if (def != null) out.add(deviceFor(DEFAULT_SINK, def, true));
            for (SinkInfo info : sinks) {
                if (def != null && info.name().equals(def.name())) continue;
                if (info.state() != PA_SINK_RUNNING && info.state() != PA_SINK_IDLE) continue;
                out.add(deviceFor(info.name(), info, false));
            }
            return out.toArray(new SystemAudioStreamDevice[0]);
        }
    }

    /**
     * Call whenever Settings change. Recomputes every device's ring length and swaps in new, empty
     * {@link com.EonCuber.audio.AudioBuffer}s where it changed. Safe from any thread.
     */
    public void settingsUpdated(){
        for (PulseMonitorDevice d : byId.values()) d.settingsUpdated();
    }

    @Override
    public void stop(){
        synchronized (lock) {
            for (PulseMonitorDevice d : devices.values()) d.deactivate();
            devices.clear();
            byId.clear();
            Session s = session;
            session = null;
            if (s != null) s.close();
        }
    }

    // ---------------------------------------------------------------- internals (under lock)

    private PulseMonitorDevice deviceFor(String key, SinkInfo info, boolean followsDefault){
        PulseMonitorDevice d = devices.get(key);
        if (d == null) {
            d = new PulseMonitorDevice(ids.getAndIncrement(), key, followsDefault, info);
            devices.put(key, d);
            byId.put(d.id, d);
        } else if (!d.isActive()) {
            d.publish(displayName(info, followsDefault), info.format());
        }
        return d;
    }

    private Session session(){
        Session s = session;
        if (s != null && !s.dead) return s;
        if (s != null) {
            for (PulseMonitorDevice d : byId.values()) d.markInactive("lost connection to the sound server");
            s.close();
        }
        try {
            s = new Session();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Throwable t) { // libpulse missing (ExceptionInInitializerError / NoClassDefFoundError), ...
            throw new IllegalStateException("libpulse (libpulse.so.0) is not available", t);
        }
        session = s;
        return s;
    }

    private static String displayName(SinkInfo info, boolean followsDefault){
        String desc = info.description() != null ? info.description() : info.name();
        return followsDefault ? "Default (" + desc + ")" : desc;
    }

    private static MemorySegment tag(long value){
        return MemorySegment.ofAddress(value);
    }

    private static String cstr(MemorySegment p){
        return p.address() == 0 ? null : p.reinterpret(Long.MAX_VALUE).getString(0);
    }

    private static Object call(MethodHandle h, Object... args){
        try {
            return h.invokeWithArguments(args);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    // ---------------------------------------------------------------- sink info

    private record SinkInfo(String name, int index, String description, int rate, int channels,
                            int[] positions, boolean validMap, String monitorName, int state) {

        static SinkInfo read(MemorySegment p){
            MemorySegment s = p.reinterpret(SINK_INFO.byteSize());
            int channels = s.get(JAVA_BYTE, SI_SPEC + SPEC_CHANNELS) & 0xFF;
            int mapChannels = s.get(JAVA_BYTE, SI_MAP + MAP_CHANNELS) & 0xFF;
            int[] positions = new int[channels];
            if (mapChannels == channels) {
                for (int i = 0; i < channels; i++) positions[i] = s.get(JAVA_INT, SI_MAP + MAP_MAP + 4L * i);
            } else {
                // no usable map: request WAVE order explicitly so we know exactly what arrives
                Speaker[] wave = defaultOrder(channels);
                for (int i = 0; i < channels; i++) positions[i] = PulseBindings.position(wave[i], i);
            }
            return new SinkInfo(
                    cstr(s.get(ADDRESS, SI_NAME)),
                    s.get(JAVA_INT, SI_INDEX),
                    cstr(s.get(ADDRESS, SI_DESCRIPTION)),
                    s.get(JAVA_INT, SI_SPEC + SPEC_RATE),
                    channels,
                    positions,
                    true,
                    cstr(s.get(ADDRESS, SI_MONITOR_NAME)),
                    s.get(JAVA_INT, SI_STATE));
        }

        /** The stream is always requested as native-endian float32 at the sink's rate and channel map. */
        NativeFormat format(){
            Speaker[] speakers = new Speaker[channels];
            for (int i = 0; i < channels; i++) speakers[i] = PulseBindings.speaker(positions[i]);
            return new NativeFormat(rate, SampleFormat.F32, ByteOrder.nativeOrder(), speakers);
        }
    }

    // ---------------------------------------------------------------- device

    private final class PulseMonitorDevice extends NativeCaptureDevice {
        final long id;
        final String key;
        final boolean followsDefault;

        // guarded by the pulse mainloop lock
        boolean wanted;
        boolean opening;
        MemorySegment stream = MemorySegment.NULL;
        String boundSink;
        int boundIndex = -1;
        int frameBytes = 1;
        int consecutiveFailures; // reset once audio flows

        PulseMonitorDevice(long id, String key, boolean followsDefault, SinkInfo info){
            super(lock, displayName(info, followsDefault), info.format());
            this.id = id;
            this.key = key;
            this.followsDefault = followsDefault;
        }

        @Override
        protected void openNative(){
            byId.put(id, this);
            devices.putIfAbsent(key, this);
            Session s = session();
            s.lock();
            try {
                SinkInfo info = s.querySink(followsDefault ? DEFAULT_SINK : key);
                if (info == null) throw new IllegalStateException("Sink '" + key + "' is no longer available");
                wanted = true;
                opening = true;
                consecutiveFailures = 0;
                createStream(s, info);
                s.awaitStreamReady(stream);
            } catch (RuntimeException e) {
                wanted = false;
                destroyStream(s);
                throw e;
            } finally {
                opening = false;
                s.unlock();
            }
        }

        @Override
        protected void closeNative(){
            Session s = session;
            if (s == null) return;
            s.lock();
            try {
                wanted = false;
                destroyStream(s);
            } finally {
                s.unlock();
            }
        }

        /** Mainloop thread: switch to another sink (default changed) or a changed format. */
        void rebind(Session s, SinkInfo info){
            destroyStream(s);
            try {
                createStream(s, info);
            } catch (RuntimeException e) {
                wanted = false;
                destroyStream(s);
                markInactive(String.valueOf(e.getMessage()));
            }
        }

        void createStream(Session s, SinkInfo info){
            if (info.monitorName() == null) throw new IllegalStateException("Sink '" + info.name() + "' has no monitor source");
            publish(displayName(info, followsDefault), info.format());
            int channels = info.channels();
            try (Arena a = Arena.ofConfined()) {
                MemorySegment spec = a.allocate(SAMPLE_SPEC);
                spec.set(JAVA_INT, SPEC_FORMAT, ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? PA_SAMPLE_FLOAT32LE : PA_SAMPLE_FLOAT32BE);
                spec.set(JAVA_INT, SPEC_RATE, info.rate());
                spec.set(JAVA_BYTE, SPEC_CHANNELS, (byte) channels);
                MemorySegment map = MemorySegment.NULL;
                if (info.validMap()) {
                    map = a.allocate(CHANNEL_MAP);
                    map.set(JAVA_BYTE, MAP_CHANNELS, (byte) channels);
                    for (int i = 0; i < channels; i++) map.set(JAVA_INT, MAP_MAP + 4L * i, info.positions()[i]);
                }
                MemorySegment st = (MemorySegment) call(pa_stream_new, s.ctx, a.allocateFrom("Avis system audio"), spec, map);
                if (st.address() == 0) throw new IllegalStateException("pa_stream_new failed: " + s.error());
                call(pa_stream_set_state_callback, st, s.streamStateStub, tag(id));
                call(pa_stream_set_read_callback, st, s.streamReadStub, tag(id));
                stream = st;
                boundSink = info.name();
                boundIndex = info.index();
                frameBytes = channels * 4;

                MemorySegment attr = a.allocate(BUFFER_ATTR);
                attr.fill((byte) 0xFF); // (uint32_t) -1 = server default
                attr.set(JAVA_INT, BUFFER_ATTR.byteOffset(MemoryLayout.PathElement.groupElement("fragsize")),
                        targetPeriodFrames(info.rate()) * frameBytes);
                int r = (int) call(pa_stream_connect_record, st, a.allocateFrom(info.monitorName()), attr,
                        PA_STREAM_ADJUST_LATENCY | PA_STREAM_DONT_MOVE);
                if (r < 0) throw new IllegalStateException("Could not record from '" + info.monitorName() + "': " + s.error());
            }
        }

        void destroyStream(Session s){
            MemorySegment st = stream;
            if (st.address() == 0) return;
            stream = MemorySegment.NULL;
            boundIndex = -1;
            try {
                call(pa_stream_set_state_callback, st, MemorySegment.NULL, MemorySegment.NULL);
                call(pa_stream_set_read_callback, st, MemorySegment.NULL, MemorySegment.NULL);
                call(pa_stream_disconnect, st);
            } catch (RuntimeException e) {
                log("pa_stream_disconnect failed", e);
            }
            call(pa_stream_unref, st);
        }
    }

    // ---------------------------------------------------------------- libpulse session

    private final class Session {
        final Arena arena = Arena.ofShared();
        final MemorySegment ml;
        final MemorySegment ctx;
        final MemorySegment contextStateStub, serverInfoStub, sinkInfoStub, subscribeStub, streamStateStub, streamReadStub;
        // only used on the mainloop thread
        final MemorySegment peekData, peekSize;
        // filled on the mainloop thread while a Java thread waits on the mainloop
        final List<SinkInfo> syncSinks = new ArrayList<>();
        volatile boolean dead;
        private boolean closed;

        Session(){
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            contextStateStub = stub(lookup, "onContextState", CONTEXT_NOTIFY_CB);
            serverInfoStub = stub(lookup, "onServerInfo", SERVER_INFO_CB);
            sinkInfoStub = stub(lookup, "onSinkInfo", SINK_INFO_CB);
            subscribeStub = stub(lookup, "onSubscribe", SUBSCRIBE_CB);
            streamStateStub = stub(lookup, "onStreamState", STREAM_NOTIFY_CB);
            streamReadStub = stub(lookup, "onStreamRead", STREAM_REQUEST_CB);
            peekData = arena.allocate(ADDRESS);
            peekSize = arena.allocate(JAVA_LONG);

            ml = (MemorySegment) call(pa_threaded_mainloop_new);
            if (ml.address() == 0) {
                arena.close();
                throw new IllegalStateException("pa_threaded_mainloop_new failed");
            }
            MemorySegment c = MemorySegment.NULL;
            try {
                call(pa_threaded_mainloop_set_name, ml, arena.allocateFrom("avis-pulse"));
                MemorySegment api = (MemorySegment) call(pa_threaded_mainloop_get_api, ml);
                c = (MemorySegment) call(pa_context_new, api, arena.allocateFrom("Avis"));
                if (c.address() == 0) throw new IllegalStateException("pa_context_new failed");
                call(pa_context_set_state_callback, c, contextStateStub, MemorySegment.NULL);
                if ((int) call(pa_context_connect, c, MemorySegment.NULL, PA_CONTEXT_NOFLAGS, MemorySegment.NULL) < 0)
                    throw new IllegalStateException("Could not connect to the sound server: " + errorOf(c));
                if ((int) call(pa_threaded_mainloop_start, ml) < 0)
                    throw new IllegalStateException("pa_threaded_mainloop_start failed");
            } catch (RuntimeException e) {
                if (c.address() != 0) call(pa_context_unref, c);
                call(pa_threaded_mainloop_free, ml);
                arena.close();
                throw e;
            }
            ctx = c;
            lock();
            try {
                while (true) {
                    int st = (int) call(pa_context_get_state, ctx);
                    if (st == PA_CONTEXT_READY) break;
                    if (st == PA_CONTEXT_FAILED || st == PA_CONTEXT_TERMINATED)
                        throw new IllegalStateException("Could not connect to the PulseAudio/PipeWire server: " + error());
                    waitLoop();
                }
                call(pa_context_set_subscribe_callback, ctx, subscribeStub, MemorySegment.NULL);
                unrefOp((MemorySegment) call(pa_context_subscribe, ctx,
                        PA_SUBSCRIPTION_MASK_SINK | PA_SUBSCRIPTION_MASK_SERVER, MemorySegment.NULL, MemorySegment.NULL));
            } catch (RuntimeException e) {
                unlock();
                close();
                throw e;
            }
            unlock();
        }

        private MemorySegment stub(MethodHandles.Lookup lookup, String name, FunctionDescriptor fd){
            try {
                MethodHandle target = lookup.findVirtual(Session.class, name, fd.toMethodType()).bindTo(this);
                return LINKER.upcallStub(target, fd, arena);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        void lock(){ call(pa_threaded_mainloop_lock, ml); }
        void unlock(){ call(pa_threaded_mainloop_unlock, ml); }
        void waitLoop(){ call(pa_threaded_mainloop_wait, ml); }
        void signal(){ call(pa_threaded_mainloop_signal, ml, 0); }

        String error(){ return errorOf(ctx); }

        private String errorOf(MemorySegment c){
            int e = (int) call(pa_context_errno, c);
            return cstr((MemorySegment) call(pa_strerror, e));
        }

        void unrefOp(MemorySegment op){
            if (op.address() != 0) call(pa_operation_unref, op);
        }

        /** Java thread, mainloop lock held. */
        private void await(MemorySegment op){
            if (op.address() == 0) throw new IllegalStateException("libpulse request failed: " + error());
            try {
                while ((int) call(pa_operation_get_state, op) == PA_OPERATION_RUNNING) waitLoop();
            } finally {
                call(pa_operation_unref, op);
            }
        }

        /** Java thread, mainloop lock held. Returns null if the sink doesn't exist. */
        SinkInfo querySink(String name){
            syncSinks.clear();
            try (Arena a = Arena.ofConfined()) {
                await((MemorySegment) call(pa_context_get_sink_info_by_name, ctx, a.allocateFrom(name), sinkInfoStub, tag(TAG_SYNC)));
            }
            return syncSinks.isEmpty() ? null : syncSinks.getFirst();
        }

        /** Java thread, mainloop lock held. */
        List<SinkInfo> querySinks(){
            syncSinks.clear();
            await((MemorySegment) call(pa_context_get_sink_info_list, ctx, sinkInfoStub, tag(TAG_SYNC)));
            return new ArrayList<>(syncSinks);
        }

        /** Java thread, mainloop lock held. */
        void awaitStreamReady(MemorySegment st){
            while (true) {
                if (dead) throw new IllegalStateException("Lost connection to the sound server");
                int state = (int) call(pa_stream_get_state, st);
                if (state == PA_STREAM_READY) return;
                if (state == PA_STREAM_FAILED || state == PA_STREAM_TERMINATED)
                    throw new IllegalStateException("Recording stream failed: " + error());
                waitLoop();
            }
        }

        /** Java thread holding {@link #lock}. */
        void close(){
            if (closed) return;
            closed = true;
            lock();
            try {
                for (PulseMonitorDevice d : byId.values()) {
                    d.wanted = false;
                    d.destroyStream(this);
                }
                call(pa_context_set_subscribe_callback, ctx, MemorySegment.NULL, MemorySegment.NULL);
                call(pa_context_set_state_callback, ctx, MemorySegment.NULL, MemorySegment.NULL);
                call(pa_context_disconnect, ctx);
            } catch (RuntimeException e) {
                log("Error while disconnecting from the sound server", e);
            } finally {
                unlock();
            }
            call(pa_threaded_mainloop_stop, ml);
            call(pa_context_unref, ctx);
            call(pa_threaded_mainloop_free, ml);
            arena.close(); // only after the mainloop thread is gone: it owns the upcall stubs
        }

        // ------------------------------------------------------------ callbacks (mainloop thread, never throw)

        void onContextState(MemorySegment c, MemorySegment userdata){
            try {
                int st = (int) call(pa_context_get_state, c);
                if (st == PA_CONTEXT_FAILED || st == PA_CONTEXT_TERMINATED) {
                    if (!dead && st == PA_CONTEXT_FAILED) {
                        for (PulseMonitorDevice d : byId.values()) {
                            // a Java thread waiting in awaitStreamReady owns that stream: it sees FAILED and cleans up
                            if (!d.wanted || d.opening) continue;
                            d.wanted = false;
                            d.destroyStream(this);
                            d.markInactive("lost connection to the sound server");
                        }
                    }
                    dead = true;
                }
                signal();
            } catch (Throwable t) {
                log("pulse context callback failed", t);
            }
        }

        void onServerInfo(MemorySegment c, MemorySegment info, MemorySegment userdata){
            try {
                if (info.address() == 0) return;
                String def = cstr(info.reinterpret(SERVER_INFO.byteSize()).get(ADDRESS, SRV_DEFAULT_SINK));
                if (def == null) return;
                for (PulseMonitorDevice d : byId.values()) {
                    if (!d.followsDefault || !d.wanted || def.equals(d.boundSink)) continue;
                    try (Arena a = Arena.ofConfined()) {
                        unrefOp((MemorySegment) call(pa_context_get_sink_info_by_name, ctx, a.allocateFrom(def),
                                sinkInfoStub, tag(TAG_FOLLOW_DEFAULT)));
                    }
                    return;
                }
            } catch (Throwable t) {
                log("pulse server-info callback failed", t);
            }
        }

        void onSinkInfo(MemorySegment c, MemorySegment info, int eol, MemorySegment userdata){
            try {
                long tag = userdata.address();
                if (tag == TAG_SYNC) {
                    if (eol == 0 && info.address() != 0) syncSinks.add(SinkInfo.read(info));
                    signal();
                    return;
                }
                if (eol != 0 || info.address() == 0) return;
                SinkInfo si = SinkInfo.read(info);
                for (PulseMonitorDevice d : byId.values()) {
                    if (!d.wanted || d.opening) continue;
                    if (tag == TAG_FOLLOW_DEFAULT && d.followsDefault) {
                        if (!si.name().equals(d.boundSink) || d.stream.address() == 0) d.rebind(this, si);
                    } else if (tag == TAG_FOLLOW_INDEX && d.boundIndex == si.index()
                            && !si.format().sameAs(d.state().format())) {
                        d.rebind(this, si); // the sink's rate / layout changed under us
                    }
                }
            } catch (Throwable t) {
                log("pulse sink-info callback failed", t);
            }
        }

        void onSubscribe(MemorySegment c, int type, int index, MemorySegment userdata){
            try {
                int facility = type & PA_SUBSCRIPTION_EVENT_FACILITY_MASK;
                int kind = type & PA_SUBSCRIPTION_EVENT_TYPE_MASK;
                if (facility == PA_SUBSCRIPTION_EVENT_SERVER) {
                    unrefOp((MemorySegment) call(pa_context_get_server_info, ctx, serverInfoStub, MemorySegment.NULL));
                } else if (facility == PA_SUBSCRIPTION_EVENT_SINK && kind == PA_SUBSCRIPTION_EVENT_CHANGE) {
                    for (PulseMonitorDevice d : byId.values()) {
                        if (d.wanted && d.boundIndex == index) {
                            unrefOp((MemorySegment) call(pa_context_get_sink_info_by_index, ctx, index, sinkInfoStub, tag(TAG_FOLLOW_INDEX)));
                            break;
                        }
                    }
                }
            } catch (Throwable t) {
                log("pulse subscribe callback failed", t);
            }
        }

        void onStreamState(MemorySegment st, MemorySegment userdata){
            try {
                signal();
                PulseMonitorDevice d = byId.get(userdata.address());
                if (d == null || d.opening || d.stream.address() != st.address()) return;
                int state = (int) call(pa_stream_get_state, st);
                if (state != PA_STREAM_FAILED && state != PA_STREAM_TERMINATED) return;
                d.destroyStream(this);
                if (d.followsDefault && d.wanted && !dead && ++d.consecutiveFailures <= 5) {
                    // the default sink probably went away: re-resolve the (new) default
                    d.boundSink = null;
                    unrefOp((MemorySegment) call(pa_context_get_server_info, ctx, serverInfoStub, MemorySegment.NULL));
                } else {
                    d.wanted = false;
                    d.markInactive(state == PA_STREAM_FAILED ? "stream failed: " + error() : "stream terminated");
                }
            } catch (Throwable t) {
                log("pulse stream-state callback failed", t);
            }
        }

        void onStreamRead(MemorySegment st, long nbytes, MemorySegment userdata){
            try {
                PulseMonitorDevice d = byId.get(userdata.address());
                while (true) {
                    long readable = (long) pa_stream_readable_size.invokeExact(st);
                    if (readable <= 0) break; // 0, or (size_t) -1 on error
                    if ((int) pa_stream_peek.invokeExact(st, peekData, peekSize) < 0) break;
                    long size = peekSize.get(JAVA_LONG, 0);
                    if (size == 0) break;
                    MemorySegment data = peekData.get(ADDRESS, 0);
                    if (d != null && d.stream.address() == st.address()) {
                        d.consecutiveFailures = 0;
                        int fb = d.frameBytes;
                        if (data.address() == 0) d.deliverSilence(size / fb); // hole in the stream
                        else d.deliverInterleaved(data.reinterpret(size), (int) (size / fb));
                    }
                    int ignored = (int) pa_stream_drop.invokeExact(st);
                }
            } catch (Throwable t) {
                log("pulse read callback failed", t);
            }
        }
    }
}
