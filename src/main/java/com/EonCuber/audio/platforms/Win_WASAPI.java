package com.EonCuber.audio.platforms;

import com.EonCuber.audio.SystemAudioStreamDevice;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static com.EonCuber.audio.platforms.ComBindings.*;
import static com.EonCuber.audio.platforms.NativeCaptureDevice.*;
import static java.lang.foreign.ValueLayout.*;

/**
 * System audio capture through WASAPI shared-mode loopback, driven through raw COM vtables.
 * <p>
 * Device 0 is the default render endpoint (eConsole) and follows default-device changes live through a native
 * IMMNotificationClient. The rest are the other active render endpoints. Loopback is initialized with the endpoint's
 * mix format, so capture is 1:1.
 * <p>
 * Latency: loopback only delivers once per render-engine period, which is 10 ms by default. Each capture therefore
 * opens a <b>silent companion render stream</b> on the same endpoint, using IAudioClient3 at the driver's minimum
 * period, which pulls the engine (and so the loopback packets) down to about 3 ms where the driver supports it. It
 * also keeps loopback flowing while the system is silent. Fallbacks, in order: IAudioClient3 companion, then a plain
 * IAudioClient companion at the default period, then loopback alone, polled every 1 ms with synthesized silence
 * while idle.
 * <p>
 * Threading: all COM work happens on platform-owned MTA threads: one control thread (enumeration and
 * notifications) plus one capture thread per active device. Caller threads never touch COM.
 */
public class Win_WASAPI implements AudioPlatform {
    private static final String DEFAULT_KEY = "<default>";
    private static final long REFTIMES_PER_SEC = 10_000_000L;

    private final Object lock = new Object();
    private final Map<String, WasapiDevice> devices = new ConcurrentHashMap<>();
    private volatile WasapiDevice defaultDevice;
    private Control control; // guarded by lock

    public Win_WASAPI(){}

    @Override
    public SystemAudioStreamDevice[] availableDevices(){
        synchronized (lock) {
            List<EndpointInfo> endpoints = control().call(Win_WASAPI::enumerate);
            List<SystemAudioStreamDevice> out = new ArrayList<>();
            for (EndpointInfo ep : endpoints) {
                String key = ep.isDefault() ? DEFAULT_KEY : ep.id();
                WasapiDevice d = devices.get(key);
                String name = ep.isDefault() ? "Default (" + ep.name() + ")" : ep.name();
                if (d == null) {
                    d = new WasapiDevice(ep.isDefault() ? null : ep.id(), key, name, ep.format());
                    devices.put(key, d);
                    if (ep.isDefault()) defaultDevice = d;
                } else if (!d.isActive()) {
                    d.publish(name, ep.format());
                }
                out.add(d);
            }
            return out.toArray(new SystemAudioStreamDevice[0]);
        }
    }

    /**
     * Call whenever Settings change. Recomputes every device's ring length and swaps in new, empty
     * {@link com.EonCuber.audio.AudioBuffer}s where it changed. Safe from any thread.
     */
    public void settingsUpdated(){
        for (WasapiDevice d : devices.values()) d.settingsUpdated();
    }

    @Override
    public void stop(){
        synchronized (lock) {
            for (WasapiDevice d : devices.values()) d.deactivate();
            devices.clear();
            defaultDevice = null;
            if (control != null) {
                control.shutdown();
                control = null;
            }
        }
    }

    private Control control(){
        if (control == null) {
            try {
                control = new Control();
            } catch (IllegalStateException e) {
                throw e;
            } catch (Throwable t) {
                throw new IllegalStateException("WASAPI is not available", t);
            }
        }
        return control;
    }

    // ---------------------------------------------------------------- enumeration (control thread)

    private record EndpointInfo(String id, String name, NativeFormat format, boolean isDefault) {}

    private static List<EndpointInfo> enumerate(MemorySegment enumerator) throws Throwable {
        List<EndpointInfo> out = new ArrayList<>();
        try (Arena a = Arena.ofConfined()) {
            MemorySegment pp = a.allocate(ADDRESS);
            String defaultId = null;
            int hr = (int) V_PIIP.invokeExact(fn(enumerator, ENUM_GetDefaultAudioEndpoint), enumerator, eRender, eConsole, pp);
            if (hr >= 0) {
                MemorySegment dev = pp.get(ADDRESS, 0);
                try {
                    EndpointInfo info = describe(dev, true);
                    defaultId = info.id();
                    if (info.format() != null) out.add(info);
                } finally {
                    release(dev);
                }
            }
            check((int) V_PIIP.invokeExact(fn(enumerator, ENUM_EnumAudioEndpoints), enumerator, eRender, DEVICE_STATE_ACTIVE, pp),
                    "IMMDeviceEnumerator::EnumAudioEndpoints");
            MemorySegment collection = pp.get(ADDRESS, 0);
            try {
                MemorySegment count = a.allocate(JAVA_INT);
                check((int) V_PP.invokeExact(fn(collection, COLL_GetCount), collection, count), "IMMDeviceCollection::GetCount");
                for (int i = 0; i < count.get(JAVA_INT, 0); i++) {
                    if ((int) V_PIP.invokeExact(fn(collection, COLL_Item), collection, i, pp) < 0) continue;
                    MemorySegment dev = pp.get(ADDRESS, 0);
                    try {
                        EndpointInfo info = describe(dev, false);
                        if (info.format() == null || info.id().equals(defaultId)) continue;
                        out.add(info);
                    } finally {
                        release(dev);
                    }
                }
            } finally {
                release(collection);
            }
        }
        return out;
    }

    /** id, friendly name and mix format (null if unusable) of an endpoint. */
    private static EndpointInfo describe(MemorySegment device, boolean isDefault) throws Throwable {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment pp = a.allocate(ADDRESS);
            check((int) V_PP.invokeExact(fn(device, DEV_GetId), device, pp), "IMMDevice::GetId");
            MemorySegment idPtr = pp.get(ADDRESS, 0);
            String id = wideString(idPtr);
            coTaskMemFree(idPtr);
            String name = friendlyName(device);
            NativeFormat format = null;
            if ((int) V_ACTIVATE.invokeExact(fn(device, DEV_Activate), device, IID_IAudioClient, CLSCTX_ALL, MemorySegment.NULL, pp) >= 0) {
                MemorySegment client = pp.get(ADDRESS, 0);
                try {
                    if ((int) V_PP.invokeExact(fn(client, AC_GetMixFormat), client, pp) >= 0) {
                        MemorySegment wfx = pp.get(ADDRESS, 0);
                        format = parseFormat(wfx);
                        coTaskMemFree(wfx);
                    }
                } finally {
                    release(client);
                }
            }
            return new EndpointInfo(id, name != null ? name : id, format, isDefault);
        }
    }

    private static String friendlyName(MemorySegment device) throws Throwable {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment pp = a.allocate(ADDRESS);
            if ((int) V_PIP.invokeExact(fn(device, DEV_OpenPropertyStore), device, STGM_READ, pp) < 0) return null;
            MemorySegment store = pp.get(ADDRESS, 0);
            MemorySegment pv = a.allocate(PROPVARIANT_SIZE, 8);
            try {
                if ((int) V_PPP.invokeExact(fn(store, PROPS_GetValue), store, PKEY_Device_FriendlyName, pv) < 0) return null;
                String name = pv.get(JAVA_SHORT, 0) == VT_LPWSTR ? wideString(pv.get(ADDRESS, 8)) : null;
                int ignored = (int) PropVariantClear.invokeExact(pv);
                return name;
            } finally {
                release(store);
            }
        }
    }

    /** WAVEFORMATEX / WAVEFORMATEXTENSIBLE → NativeFormat, or null if it's not a PCM/float layout we can read. */
    static NativeFormat parseFormat(MemorySegment wfxPtr){
        MemorySegment w = wfxPtr.reinterpret(18);
        int tag = w.get(JAVA_SHORT_UNALIGNED, 0) & 0xFFFF;
        int channels = w.get(JAVA_SHORT_UNALIGNED, 2) & 0xFFFF;
        int rate = w.get(JAVA_INT_UNALIGNED, 4);
        int blockAlign = w.get(JAVA_SHORT_UNALIGNED, 12) & 0xFFFF;
        int bits = w.get(JAVA_SHORT_UNALIGNED, 14) & 0xFFFF;
        int cbSize = w.get(JAVA_SHORT_UNALIGNED, 16) & 0xFFFF;
        int subType = tag;
        int mask = 0;
        if (tag == WAVE_FORMAT_EXTENSIBLE && cbSize >= 22) {
            w = wfxPtr.reinterpret(18 + cbSize);
            mask = w.get(JAVA_INT_UNALIGNED, 20);
            subType = w.get(JAVA_SHORT_UNALIGNED, 24) & 0xFFFF; // SubFormat.Data1 == WAVE_FORMAT_xxx
        }
        SampleFormat sf = null;
        if (subType == WAVE_FORMAT_IEEE_FLOAT) {
            if (bits == 32) sf = SampleFormat.F32;
            else if (bits == 64) sf = SampleFormat.F64;
        } else if (subType == WAVE_FORMAT_PCM) {
            sf = switch (bits) {
                case 8 -> SampleFormat.U8;
                case 16 -> SampleFormat.S16;
                case 24 -> SampleFormat.S24_PACKED;
                case 32 -> SampleFormat.S32; // also 24-in-32, which is left-justified
                default -> null;
            };
        }
        if (sf == null || channels == 0 || rate <= 0 || blockAlign != channels * sf.bytes) return null;
        return new NativeFormat(rate, sf, ByteOrder.LITTLE_ENDIAN, speakersFromMask(mask, channels));
    }

    private static final Speaker[] MASK_BITS = {
            Speaker.FL, Speaker.FR, Speaker.FC, Speaker.LFE, Speaker.BL, Speaker.BR, Speaker.FLC, Speaker.FRC,
            Speaker.BC, Speaker.SL, Speaker.SR, Speaker.TC, Speaker.TFL, Speaker.TFC, Speaker.TFR, Speaker.TBL,
            Speaker.TBC, Speaker.TBR
    };

    private static Speaker[] speakersFromMask(int mask, int channels){
        if (mask == 0) return defaultOrder(channels);
        Speaker[] out = new Speaker[channels];
        int n = 0;
        for (int bit = 0; bit < MASK_BITS.length && n < channels; bit++)
            if ((mask & (1 << bit)) != 0) out[n++] = MASK_BITS[bit];
        while (n < channels) out[n++] = Speaker.OTHER;
        if (channels == 1 && out[0] == Speaker.FC) out[0] = Speaker.MONO;
        return out;
    }

    // ---------------------------------------------------------------- control thread

    @FunctionalInterface
    private interface ComTask<T> { T run(MemorySegment enumerator) throws Throwable; }

    /** Owns the control MTA thread, its IMMDeviceEnumerator and the native IMMNotificationClient. */
    private final class Control {
        final ExecutorService exec;
        final Arena arena = Arena.ofShared();
        MemorySegment enumerator = MemorySegment.NULL; // control thread only
        MemorySegment client = MemorySegment.NULL;

        Control(){
            exec = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(() -> {
                    int hr = coInitialize();
                    try {
                        r.run();
                    } finally {
                        if (hr >= 0) coUninitialize();
                    }
                }, "Avis-WASAPI-control");
                t.setDaemon(true);
                return t;
            });
            try {
                call(e -> { init(); return null; });
            } catch (RuntimeException e) {
                exec.shutdownNow();
                arena.close();
                throw e;
            }
        }

        <T> T call(ComTask<T> task){
            Future<T> f = exec.submit(() -> {
                try {
                    return task.run(enumerator);
                } catch (Exception | Error e) {
                    throw e;
                } catch (Throwable t) {
                    throw new IllegalStateException(t);
                }
            });
            try {
                return f.get();
            } catch (ExecutionException e) {
                Throwable c = e.getCause();
                if (c instanceof RuntimeException re) throw re;
                throw new IllegalStateException(c);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while talking to WASAPI", e);
            }
        }

        private void init() throws Throwable {
            try (Arena a = Arena.ofConfined()) {
                MemorySegment pp = a.allocate(ADDRESS);
                check((int) CoCreateInstance.invokeExact(CLSID_MMDeviceEnumerator, MemorySegment.NULL, CLSCTX_ALL,
                        IID_IMMDeviceEnumerator, pp), "CoCreateInstance(MMDeviceEnumerator)");
                enumerator = pp.get(ADDRESS, 0);
            }
            client = buildNotificationClient();
            int hr = (int) V_PP.invokeExact(fn(enumerator, ENUM_RegisterEndpointNotificationCallback), enumerator, client);
            if (hr < 0) {
                log("Could not register for default-device changes (HRESULT 0x" + Integer.toHexString(hr) + ")", null);
                client = MemorySegment.NULL;
            }
        }

        void shutdown(){
            boolean unregistered = false;
            try {
                unregistered = call(e -> {
                    boolean ok = true;
                    if (client.address() != 0) {
                        ok = (int) V_PP.invokeExact(fn(enumerator, ENUM_UnregisterEndpointNotificationCallback), enumerator, client) >= 0;
                    }
                    release(enumerator);
                    enumerator = MemorySegment.NULL;
                    return ok;
                });
            } catch (RuntimeException e) {
                log("Error while shutting down WASAPI", e);
            }
            exec.shutdown();
            try {
                exec.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // the notification client's stubs live here: if Windows may still call them, leak them instead
            if (unregistered) arena.close();
        }

        /** A static COM object: {vtbl*} with an 8-entry IMMNotificationClient vtable of upcall stubs. */
        private MemorySegment buildNotificationClient() throws ReflectiveOperationException {
            MethodHandles.Lookup l = MethodHandles.lookup();
            MemorySegment[] entries = {
                    stub(l, "qi", NC_QUERY_INTERFACE),
                    stub(l, "addRefRelease", NC_ADDREF_RELEASE),
                    stub(l, "addRefRelease", NC_ADDREF_RELEASE),
                    stub(l, "deviceStateChanged", NC_STATE_CHANGED),
                    stub(l, "deviceAddedRemoved", NC_ADDED_REMOVED),
                    stub(l, "deviceAddedRemoved", NC_ADDED_REMOVED),
                    stub(l, "defaultDeviceChanged", NC_DEFAULT_CHANGED),
                    stub(l, "propertyValueChanged", NC_PROPERTY_CHANGED),
            };
            MemorySegment vtbl = arena.allocate(ADDRESS, entries.length);
            for (int i = 0; i < entries.length; i++) vtbl.setAtIndex(ADDRESS, i, entries[i]);
            MemorySegment obj = arena.allocate(ADDRESS);
            obj.set(ADDRESS, 0, vtbl);
            return obj;
        }

        private MemorySegment stub(MethodHandles.Lookup l, String name, FunctionDescriptor fd) throws ReflectiveOperationException {
            MethodHandle target = l.findVirtual(Control.class, name, fd.toMethodType()).bindTo(this);
            return LINKER.upcallStub(target, fd, arena);
        }

        // IMMNotificationClient methods: called on system threads, must not block or make COM calls
        int qi(MemorySegment self, MemorySegment riid, MemorySegment ppv){
            try {
                MemorySegment out = ppv.reinterpret(ADDRESS.byteSize());
                if (sameGuid(riid, IID_IUnknown) || sameGuid(riid, IID_IMMNotificationClient)) {
                    out.set(ADDRESS, 0, self);
                    return S_OK;
                }
                out.set(ADDRESS, 0, MemorySegment.NULL);
            } catch (Throwable t) {
                log("IMMNotificationClient::QueryInterface failed", t);
            }
            return E_NOINTERFACE;
        }

        int addRefRelease(MemorySegment self){ return 1; } // static object, lives as long as the platform
        int deviceStateChanged(MemorySegment self, MemorySegment id, int state){ return S_OK; }
        int deviceAddedRemoved(MemorySegment self, MemorySegment id){ return S_OK; }
        int propertyValueChanged(MemorySegment self, MemorySegment id, MemorySegment key){ return S_OK; }

        int defaultDeviceChanged(MemorySegment self, int flow, int role, MemorySegment id){
            try {
                if (flow == eRender && role == eConsole) {
                    WasapiDevice d = defaultDevice;
                    if (d != null) d.requestReopen();
                }
            } catch (Throwable t) {
                log("IMMNotificationClient::OnDefaultDeviceChanged failed", t);
            }
            return S_OK;
        }
    }

    private static int coInitialize(){
        try {
            int hr = (int) CoInitializeEx.invokeExact(MemorySegment.NULL, COINIT_MULTITHREADED);
            if (hr < 0 && hr != RPC_E_CHANGED_MODE) log("CoInitializeEx failed (HRESULT 0x" + Integer.toHexString(hr) + ")", null);
            return hr;
        } catch (Throwable t) {
            log("CoInitializeEx failed", t);
            return -1;
        }
    }

    private static void coUninitialize(){
        try {
            CoUninitialize.invokeExact();
        } catch (Throwable t) {
            log("CoUninitialize failed", t);
        }
    }

    private static void closeHandle(MemorySegment h){
        if (h == null || h.address() == 0) return;
        try {
            int ignored = (int) CloseHandle.invokeExact(h);
        } catch (Throwable t) {
            log("CloseHandle failed", t);
        }
    }

    private static MemorySegment createEvent() throws Throwable {
        MemorySegment h = (MemorySegment) CreateEventW.invokeExact(MemorySegment.NULL, 0, 0, MemorySegment.NULL);
        if (h.address() == 0) throw new IllegalStateException("CreateEventW failed");
        return h;
    }

    // ---------------------------------------------------------------- device

    private final class WasapiDevice extends NativeCaptureDevice {
        final String endpointId; // null = follow the default endpoint
        final String key;
        private final Object eventLock = new Object();
        private MemorySegment controlEvent = MemorySegment.NULL; // guarded by eventLock
        private Thread thread;                                   // guarded by lock
        private volatile boolean stopRequested;
        private volatile boolean reopenRequested;

        WasapiDevice(String endpointId, String key, String name, NativeFormat format){
            super(lock, name, format);
            this.endpointId = endpointId;
            this.key = key;
        }

        @Override
        protected void openNative(){
            control(); // keeps the default-device notifications running
            if (thread != null || controlEvent().address() != 0) stopThread(); // leftovers of a run that died on its own
            devices.putIfAbsent(key, this);
            if (endpointId == null) defaultDevice = this;
            stopRequested = false;
            reopenRequested = false;
            try {
                MemorySegment ev = createEvent();
                synchronized (eventLock) { controlEvent = ev; }
            } catch (RuntimeException e) {
                throw e;
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
            CompletableFuture<Void> started = new CompletableFuture<>();
            Thread t = new Thread(() -> captureMain(started), "Avis-WASAPI-capture");
            t.setDaemon(true);
            t.setPriority(Thread.MAX_PRIORITY);
            thread = t;
            t.start();
            try {
                started.get(10, TimeUnit.SECONDS);
            } catch (ExecutionException | InterruptedException | TimeoutException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                stopThread();
                Throwable c = e instanceof ExecutionException ? e.getCause() : e;
                if (c instanceof IllegalStateException ise) throw ise;
                throw new IllegalStateException("Could not start WASAPI capture of '" + deviceName() + "'", c);
            }
        }

        @Override
        protected void closeNative(){
            stopThread();
        }

        private void stopThread(){
            stopRequested = true;
            signalControl();
            Thread t = thread;
            thread = null;
            if (t != null && t != Thread.currentThread()) {
                try {
                    t.join(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            synchronized (eventLock) {
                closeHandle(controlEvent);
                controlEvent = MemorySegment.NULL;
            }
        }

        /** From the notification thread: the default endpoint changed. */
        void requestReopen(){
            reopenRequested = true;
            signalControl();
        }

        private void signalControl(){
            synchronized (eventLock) {
                if (controlEvent.address() == 0) return;
                try {
                    int ignored = (int) SetEvent.invokeExact(controlEvent);
                } catch (Throwable t) {
                    log("SetEvent failed", t);
                }
            }
        }

        private MemorySegment controlEvent(){
            synchronized (eventLock) { return controlEvent; }
        }

        // ------------------------------------------------------------ capture thread

        private void captureMain(CompletableFuture<Void> started){
            int hr = coInitialize();
            try (Arena a = Arena.ofConfined()) {
                Capture cap = new Capture(a);
                try {
                    cap.open();
                } catch (Throwable t) {
                    cap.close();
                    started.completeExceptionally(t);
                    return;
                }
                started.complete(null);
                try {
                    while (!stopRequested) {
                        try {
                            cap.run();
                        } catch (ComException e) {
                            if (e.hr != AUDCLNT_E_DEVICE_INVALIDATED && e.hr != AUDCLNT_E_RESOURCES_INVALIDATED
                                    && e.hr != AUDCLNT_E_SERVICE_NOT_RUNNING) throw e;
                            // device removed / format changed / audio service restarted: reopen below
                        }
                        if (stopRequested) break;
                        reopenRequested = false;
                        cap.close();
                        if (!reopen(cap)) break;
                    }
                } catch (Throwable t) {
                    markInactive(String.valueOf(t.getMessage()));
                } finally {
                    cap.close();
                }
            } finally {
                if (hr >= 0) coUninitialize();
            }
        }

        /** Retries opening. The default device keeps retrying (there may briefly be no default); others give up. */
        private boolean reopen(Capture cap){
            int attempts = 0;
            while (!stopRequested) {
                try {
                    cap.open();
                    return true;
                } catch (Throwable t) {
                    cap.close();
                    attempts++;
                    if (endpointId != null && attempts >= 5) {
                        markInactive("device is no longer available: " + t.getMessage());
                        return false;
                    }
                    waitOnControl(200);
                }
            }
            return false;
        }

        private void waitOnControl(int millis){
            try (Arena a = Arena.ofConfined()) {
                MemorySegment handles = a.allocate(ADDRESS);
                handles.set(ADDRESS, 0, controlEvent());
                int ignored = (int) WaitForMultipleObjects.invokeExact(1, handles, 0, millis);
            } catch (Throwable t) {
                try { Thread.sleep(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        }

        /** Native state of one open loopback session. Only used on the capture thread. */
        private final class Capture {
            final Arena a;
            final MemorySegment pp, pU32, pU32b, pU32c, pU32d, pFlags, pRefA, pRefB, handles;
            MemorySegment enumerator = MemorySegment.NULL, device = MemorySegment.NULL;
            MemorySegment loopClient = MemorySegment.NULL, captureClient = MemorySegment.NULL, loopFormat = MemorySegment.NULL;
            MemorySegment renderAudioClient = MemorySegment.NULL, renderClient = MemorySegment.NULL;
            MemorySegment renderFormat = MemorySegment.NULL, renderEvent = MemorySegment.NULL;
            boolean companion, timerRaised;
            int rate, blockAlign, renderBufferFrames;
            long periodNanos;
            long lastAudioNanos;

            Capture(Arena a){
                this.a = a;
                pp = a.allocate(ADDRESS);
                pU32 = a.allocate(JAVA_INT);
                pU32b = a.allocate(JAVA_INT);
                pU32c = a.allocate(JAVA_INT);
                pU32d = a.allocate(JAVA_INT);
                pFlags = a.allocate(JAVA_INT);
                pRefA = a.allocate(JAVA_LONG);
                pRefB = a.allocate(JAVA_LONG);
                handles = a.allocate(ADDRESS, 2);
            }

            void open() throws Throwable {
                check((int) CoCreateInstance.invokeExact(CLSID_MMDeviceEnumerator, MemorySegment.NULL, CLSCTX_ALL,
                        IID_IMMDeviceEnumerator, pp), "CoCreateInstance(MMDeviceEnumerator)");
                enumerator = pp.get(ADDRESS, 0);
                if (endpointId == null) {
                    check((int) V_PIIP.invokeExact(fn(enumerator, ENUM_GetDefaultAudioEndpoint), enumerator, eRender, eConsole, pp),
                            "GetDefaultAudioEndpoint");
                } else {
                    try (Arena tmp = Arena.ofConfined()) {
                        check((int) V_PPP.invokeExact(fn(enumerator, ENUM_GetDevice), enumerator,
                                tmp.allocateFrom(endpointId, StandardCharsets.UTF_16LE), pp), "IMMDeviceEnumerator::GetDevice");
                    }
                }
                device = pp.get(ADDRESS, 0);
                String name = friendlyName(device);
                if (name == null) name = endpointId != null ? endpointId : "Audio output";

                // loopback client, initialized with the unmodified mix format: 1:1 capture
                check((int) V_ACTIVATE.invokeExact(fn(device, DEV_Activate), device, IID_IAudioClient, CLSCTX_ALL, MemorySegment.NULL, pp),
                        "IMMDevice::Activate(IAudioClient)");
                loopClient = pp.get(ADDRESS, 0);
                check((int) V_PP.invokeExact(fn(loopClient, AC_GetMixFormat), loopClient, pp), "IAudioClient::GetMixFormat");
                loopFormat = pp.get(ADDRESS, 0);
                NativeFormat format = parseFormat(loopFormat);
                if (format == null) throw new IllegalStateException("Unsupported mix format on '" + name + "'");
                rate = format.sampleRate();
                blockAlign = format.frameBytes();
                publish(endpointId == null ? "Default (" + name + ")" : name, format);

                check((int) V_INITIALIZE.invokeExact(fn(loopClient, AC_Initialize), loopClient, AUDCLNT_SHAREMODE_SHARED,
                        AUDCLNT_STREAMFLAGS_LOOPBACK, REFTIMES_PER_SEC / 5, 0L, loopFormat, MemorySegment.NULL),
                        "IAudioClient::Initialize(loopback)");
                check((int) V_PPP.invokeExact(fn(loopClient, AC_GetService), loopClient, IID_IAudioCaptureClient, pp),
                        "IAudioClient::GetService(IAudioCaptureClient)");
                captureClient = pp.get(ADDRESS, 0);
                periodNanos = 10_000_000L;
                if ((int) V_PPP.invokeExact(fn(loopClient, AC_GetDevicePeriod), loopClient, pRefA, pRefB) >= 0)
                    periodNanos = pRefA.get(JAVA_LONG, 0) * 100L;

                openCompanion();

                check((int) V_P.invokeExact(fn(loopClient, AC_Start), loopClient), "IAudioClient::Start(loopback)");
                if (companion) {
                    int hr = (int) V_P.invokeExact(fn(renderAudioClient, AC_Start), renderAudioClient);
                    if (hr < 0) {
                        log("Companion render stream failed to start (HRESULT 0x" + Integer.toHexString(hr) + "), polling loopback instead", null);
                        closeCompanion();
                    }
                }
                if (!companion) raiseTimerResolution();
                lastAudioNanos = System.nanoTime();
            }

            /** Silent low-latency render stream on the same endpoint (see class docs). */
            private void openCompanion(){
                try {
                    renderEvent = createEvent();
                    // 1) IAudioClient3 at the minimum shared-mode engine period
                    int hr = (int) V_ACTIVATE.invokeExact(fn(device, DEV_Activate), device, IID_IAudioClient3, CLSCTX_ALL, MemorySegment.NULL, pp);
                    if (hr >= 0) {
                        MemorySegment ac3 = pp.get(ADDRESS, 0);
                        if (initLowLatency(ac3)) renderAudioClient = ac3;
                        else release(ac3);
                    }
                    // 2) plain IAudioClient at the default period
                    if (renderAudioClient.address() == 0) {
                        check((int) V_ACTIVATE.invokeExact(fn(device, DEV_Activate), device, IID_IAudioClient, CLSCTX_ALL, MemorySegment.NULL, pp),
                                "IMMDevice::Activate(IAudioClient, companion)");
                        MemorySegment ac = pp.get(ADDRESS, 0);
                        renderAudioClient = ac;
                        freeRenderFormat();
                        check((int) V_PP.invokeExact(fn(ac, AC_GetMixFormat), ac, pp), "IAudioClient::GetMixFormat(companion)");
                        renderFormat = pp.get(ADDRESS, 0);
                        check((int) V_INITIALIZE.invokeExact(fn(ac, AC_Initialize), ac, AUDCLNT_SHAREMODE_SHARED,
                                AUDCLNT_STREAMFLAGS_EVENTCALLBACK, 0L, 0L, renderFormat, MemorySegment.NULL),
                                "IAudioClient::Initialize(companion)");
                    }
                    check((int) V_PP.invokeExact(fn(renderAudioClient, AC_SetEventHandle), renderAudioClient, renderEvent),
                            "IAudioClient::SetEventHandle");
                    check((int) V_PP.invokeExact(fn(renderAudioClient, AC_GetBufferSize), renderAudioClient, pU32),
                            "IAudioClient::GetBufferSize");
                    renderBufferFrames = pU32.get(JAVA_INT, 0);
                    check((int) V_PPP.invokeExact(fn(renderAudioClient, AC_GetService), renderAudioClient, IID_IAudioRenderClient, pp),
                            "IAudioClient::GetService(IAudioRenderClient)");
                    renderClient = pp.get(ADDRESS, 0);
                    companion = true;
                    feedSilence();
                } catch (Throwable t) {
                    log("Low-latency companion stream unavailable, polling loopback instead: " + t.getMessage(), null);
                    closeCompanion();
                }
            }

            private boolean initLowLatency(MemorySegment ac3) throws Throwable {
                freeRenderFormat();
                if ((int) V_PP.invokeExact(fn(ac3, AC_GetMixFormat), ac3, pp) < 0) return false;
                renderFormat = pp.get(ADDRESS, 0);
                NativeFormat rf = parseFormat(renderFormat);
                int renderRate = rf != null ? rf.sampleRate() : rate;
                int hr = (int) V_ENGINE_PERIOD.invokeExact(fn(ac3, AC3_GetSharedModeEnginePeriod), ac3, renderFormat, pU32, pU32b, pU32c, pU32d);
                if (hr < 0) return false;
                int fundamental = Math.max(1, pU32b.get(JAVA_INT, 0));
                int min = pU32c.get(JAVA_INT, 0), max = pU32d.get(JAVA_INT, 0);
                int period = (targetPeriodFrames(renderRate) / fundamental) * fundamental;
                if (period < min) period = min;
                if (max > 0 && period > max) period = max;
                hr = (int) V_INIT_SHARED.invokeExact(fn(ac3, AC3_InitializeSharedAudioStream), ac3, AUDCLNT_STREAMFLAGS_EVENTCALLBACK,
                        period, renderFormat, MemorySegment.NULL);
                if (hr == AUDCLNT_E_ENGINE_PERIODICITY_LOCKED) {
                    // another app already fixed the engine period: join it
                    if ((int) V_PPP.invokeExact(fn(ac3, AC3_GetCurrentSharedModeEnginePeriod), ac3, pp, pU32) < 0) return false;
                    coTaskMemFree(pp.get(ADDRESS, 0));
                    period = pU32.get(JAVA_INT, 0);
                    hr = (int) V_INIT_SHARED.invokeExact(fn(ac3, AC3_InitializeSharedAudioStream), ac3, AUDCLNT_STREAMFLAGS_EVENTCALLBACK,
                            period, renderFormat, MemorySegment.NULL);
                }
                if (hr < 0) return false;
                periodNanos = Math.max(1, period * 1_000_000_000L / renderRate);
                return true;
            }

            /** Keeps the companion render buffer full of (flagged) silence. */
            private void feedSilence() throws Throwable {
                check((int) V_PP.invokeExact(fn(renderAudioClient, AC_GetCurrentPadding), renderAudioClient, pU32),
                        "IAudioClient::GetCurrentPadding");
                int frames = renderBufferFrames - pU32.get(JAVA_INT, 0);
                if (frames <= 0) return;
                check((int) V_PIP.invokeExact(fn(renderClient, REN_GetBuffer), renderClient, frames, pp), "IAudioRenderClient::GetBuffer");
                check((int) V_PII.invokeExact(fn(renderClient, REN_ReleaseBuffer), renderClient, frames, AUDCLNT_BUFFERFLAGS_SILENT),
                        "IAudioRenderClient::ReleaseBuffer");
            }

            /** Pumps audio until a stop or reopen is requested. Throws ComException on device errors. */
            void run() throws Throwable {
                long timeoutMs = Math.max(2, (2 * periodNanos) / 1_000_000L);
                while (!stopRequested && !reopenRequested) {
                    int r;
                    if (companion) {
                        handles.setAtIndex(ADDRESS, 0, renderEvent);
                        handles.setAtIndex(ADDRESS, 1, controlEvent());
                        r = (int) WaitForMultipleObjects.invokeExact(2, handles, 0, (int) timeoutMs);
                    } else {
                        handles.setAtIndex(ADDRESS, 0, controlEvent());
                        r = (int) WaitForMultipleObjects.invokeExact(1, handles, 0, 1);
                    }
                    if (r == WAIT_FAILED) throw new IllegalStateException("WaitForMultipleObjects failed");
                    if (stopRequested || reopenRequested) return;
                    if (companion) feedSilence();
                    drain();
                    if (!companion) synthesizeIdleSilence();
                }
            }

            private void drain() throws Throwable {
                while (true) {
                    check((int) V_PP.invokeExact(fn(captureClient, CAP_GetNextPacketSize), captureClient, pU32),
                            "IAudioCaptureClient::GetNextPacketSize");
                    if (pU32.get(JAVA_INT, 0) == 0) return;
                    int hr = check((int) V_CAPTURE_GETBUFFER.invokeExact(fn(captureClient, CAP_GetBuffer), captureClient, pp, pU32b,
                            pFlags, MemorySegment.NULL, MemorySegment.NULL), "IAudioCaptureClient::GetBuffer");
                    if (hr == AUDCLNT_S_BUFFER_EMPTY) return;
                    int frames = pU32b.get(JAVA_INT, 0);
                    try {
                        if ((pFlags.get(JAVA_INT, 0) & AUDCLNT_BUFFERFLAGS_SILENT) != 0) deliverSilence(frames);
                        else deliverInterleaved(pp.get(ADDRESS, 0).reinterpret((long) frames * blockAlign), frames);
                    } finally {
                        check((int) V_PI.invokeExact(fn(captureClient, CAP_ReleaseBuffer), captureClient, frames),
                                "IAudioCaptureClient::ReleaseBuffer");
                    }
                    lastAudioNanos = System.nanoTime();
                }
            }

            /** Loopback-only fallback: WASAPI sends nothing while the system is silent, so fill the gap with zeros. */
            private void synthesizeIdleSilence(){
                long now = System.nanoTime();
                long idle = now - lastAudioNanos;
                if (idle <= 2 * periodNanos) return;
                long frames = idle * rate / 1_000_000_000L;
                if (frames <= 0) return;
                deliverSilence(frames);
                lastAudioNanos += frames * 1_000_000_000L / rate;
            }

            private void raiseTimerResolution(){
                try {
                    timerRaised = (int) timeBeginPeriod.invokeExact(1) == 0;
                } catch (Throwable t) {
                    timerRaised = false;
                }
            }

            private void freeRenderFormat(){
                coTaskMemFree(renderFormat);
                renderFormat = MemorySegment.NULL;
            }

            private void closeCompanion(){
                if (renderAudioClient.address() != 0) {
                    try {
                        int ignored = (int) V_P.invokeExact(fn(renderAudioClient, AC_Stop), renderAudioClient);
                    } catch (Throwable t) {
                        log("IAudioClient::Stop(companion) failed", t);
                    }
                }
                release(renderClient);
                release(renderAudioClient);
                renderClient = MemorySegment.NULL;
                renderAudioClient = MemorySegment.NULL;
                freeRenderFormat();
                closeHandle(renderEvent);
                renderEvent = MemorySegment.NULL;
                companion = false;
            }

            void close(){
                closeCompanion();
                if (loopClient.address() != 0) {
                    try {
                        int ignored = (int) V_P.invokeExact(fn(loopClient, AC_Stop), loopClient);
                    } catch (Throwable t) {
                        log("IAudioClient::Stop(loopback) failed", t);
                    }
                }
                release(captureClient);
                release(loopClient);
                release(device);
                release(enumerator);
                coTaskMemFree(loopFormat);
                captureClient = loopClient = device = enumerator = loopFormat = MemorySegment.NULL;
                if (timerRaised) {
                    try {
                        int ignored = (int) timeEndPeriod.invokeExact(1);
                    } catch (Throwable t) {
                        log("timeEndPeriod failed", t);
                    }
                    timerRaised = false;
                }
            }
        }
    }
}
