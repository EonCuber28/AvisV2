package com.EonCuber.audio.platforms;

import com.EonCuber.audio.SystemAudioStreamDevice;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.EonCuber.audio.platforms.MacBindings.*;
import static com.EonCuber.audio.platforms.NativeCaptureDevice.log;

/**
 * System audio capture on macOS.
 * <ul>
 *   <li><b>14.2+</b>: Core Audio process taps ({@link MacOS_ProcessTapDevice}). Device 0 is the default output and
 *   follows default-output changes live; the rest are the other output devices that are running. 1:1 capture of
 *   each device's stream 0 with a ~3 ms IO buffer. Needs the "System Audio Recording" permission; without it the tap
 *   delivers silence.</li>
 *   <li><b>13.0–14.1</b>: one ScreenCaptureKit device ({@link MacOS_ScreenCaptureKitDevice}) with the stereo system
 *   mix. Needs the Screen Recording permission.</li>
 *   <li><b>older</b>: no devices.</li>
 * </ul>
 * Threading: Core Audio property listeners only enqueue work onto a platform-owned control thread, which rebuilds
 * taps under {@link #lock}. Audio arrives on Core Audio's IO thread or SCK's dispatch queue.
 */
public class MacOS_CoreAudio implements AudioPlatform {
    private static final String DEFAULT_KEY = "<default>";
    private enum Backend { PROCESS_TAP, SCREEN_CAPTURE_KIT, NONE }

    private final Object lock = new Object();
    private final Map<String, NativeCaptureDevice> devices = new ConcurrentHashMap<>();
    private final Map<Long, MacOS_ProcessTapDevice> tapsById = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong(1);
    private Backend backend;              // guarded by lock
    // rebuilds run here; the thread exits when idle, so there's nothing to shut down
    private final ThreadPoolExecutor control = new ThreadPoolExecutor(1, 1, 5, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(), r -> {
                Thread t = new Thread(r, "Avis-CoreAudio-control");
                t.setDaemon(true);
                return t;
            });
    private MemorySegment listenerStub;    // guarded by lock (lives in the global arena)
    private boolean defaultListener;       // guarded by lock

    public MacOS_CoreAudio(){
        control.allowCoreThreadTimeOut(true);
    }

    @Override
    public SystemAudioStreamDevice[] availableDevices(){
        synchronized (lock) {
            Backend b = backend();
            if (b == Backend.NONE) return new SystemAudioStreamDevice[0];
            if (b == Backend.SCREEN_CAPTURE_KIT) {
                NativeCaptureDevice d = devices.computeIfAbsent(DEFAULT_KEY, k -> new MacOS_ScreenCaptureKitDevice(lock));
                return new SystemAudioStreamDevice[]{d};
            }
            if (!defaultListener) defaultListener = addListener(kAudioObjectSystemObject, kAudioHardwarePropertyDefaultOutputDevice, 0);

            List<SystemAudioStreamDevice> out = new ArrayList<>();
            int def = defaultOutputDevice();
            if (def != 0) {
                MacOS_ProcessTapDevice.DeviceInfo info = MacOS_ProcessTapDevice.DeviceInfo.describe(def);
                if (info != null) out.add(tapDevice(DEFAULT_KEY, null, info));
            }
            for (int id : getIntArray(kAudioObjectSystemObject, kAudioHardwarePropertyDevices, SCOPE_GLOBAL)) {
                if (id == def) continue;
                MacOS_ProcessTapDevice.DeviceInfo info = MacOS_ProcessTapDevice.DeviceInfo.describe(id);
                if (info == null || !info.running() || info.uid().startsWith(MacOS_ProcessTapDevice.AGGREGATE_UID_PREFIX)) continue;
                out.add(tapDevice(info.uid(), info.uid(), info));
            }
            return out.toArray(new SystemAudioStreamDevice[0]);
        }
    }

    /**
     * Call whenever Settings change. Recomputes every device's ring length and swaps in new, empty
     * {@link com.EonCuber.audio.AudioBuffer}s where it changed. Safe from any thread.
     */
    public void settingsUpdated(){
        for (NativeCaptureDevice d : devices.values()) d.settingsUpdated();
    }

    @Override
    public void stop(){
        synchronized (lock) {
            for (NativeCaptureDevice d : devices.values()) d.deactivate();
            devices.clear();
            tapsById.clear();
            if (defaultListener) removeListener(kAudioObjectSystemObject, kAudioHardwarePropertyDefaultOutputDevice, 0);
            defaultListener = false;
            control.getQueue().clear();
        }
    }

    // ---------------------------------------------------------------- internals

    private Backend backend(){
        if (backend != null) return backend;
        try {
            int[] version = osVersion();
            boolean atLeast142 = version[0] > 14 || (version[0] == 14 && version[1] >= 2);
            if (atLeast142 && AudioHardwareCreateProcessTap != null && cls("CATapDescription").address() != 0) {
                backend = Backend.PROCESS_TAP;
            } else if (version[0] >= 13 && MacOS_ScreenCaptureKitDevice.Sck.available()) {
                backend = Backend.SCREEN_CAPTURE_KIT;
            } else {
                log("macOS " + System.getProperty("os.version") + " has no supported system-audio capture API (needs 13.0+)", null);
                backend = Backend.NONE;
            }
        } catch (Throwable t) {
            throw new IllegalStateException("Core Audio is not available", t);
        }
        return backend;
    }

    private static int[] osVersion(){
        String[] parts = System.getProperty("os.version", "0").split("\\.");
        int[] v = new int[2];
        for (int i = 0; i < 2 && i < parts.length; i++) {
            try {
                v[i] = Integer.parseInt(parts[i].replaceAll("\\D.*", ""));
            } catch (NumberFormatException ignored) {
                v[i] = 0;
            }
        }
        return v;
    }

    private MacOS_ProcessTapDevice tapDevice(String key, String uid, MacOS_ProcessTapDevice.DeviceInfo info){
        String name = MacOS_ProcessTapDevice.displayName(info, uid == null);
        NativeCaptureDevice existing = devices.get(key);
        if (existing instanceof MacOS_ProcessTapDevice d) {
            if (!d.isActive()) d.publish(name, info.format());
            return d;
        }
        MacOS_ProcessTapDevice d = new MacOS_ProcessTapDevice(this, lock, ids.getAndIncrement(), uid, name, info);
        devices.put(key, d);
        tapsById.put(d.id, d);
        return d;
    }

    /** Called (under lock) when a tap device activates: makes sure it's registered and the default is followed. */
    void adopt(MacOS_ProcessTapDevice d){
        devices.putIfAbsent(d.uid == null ? DEFAULT_KEY : d.uid, d);
        tapsById.putIfAbsent(d.id, d);
        if (d.uid == null && !defaultListener)
            defaultListener = addListener(kAudioObjectSystemObject, kAudioHardwarePropertyDefaultOutputDevice, 0);
    }

    // ---------------------------------------------------------------- property listeners

    /** Registers the platform listener for {@code selector} on {@code objectId}; {@code tag} 0 = system. */
    boolean addListener(int objectId, int selector, long tag){
        try (Arena a = Arena.ofConfined()) {
            int st = (int) AudioObjectAddPropertyListener.invokeExact(objectId, propertyAddress(a, selector, SCOPE_GLOBAL),
                    listenerStub(), MemorySegment.ofAddress(tag));
            if (st != 0) log("AudioObjectAddPropertyListener(" + fourCCString(selector) + ") failed: " + fourCCString(st), null);
            return st == 0;
        } catch (Throwable t) {
            log("AudioObjectAddPropertyListener failed", t);
            return false;
        }
    }

    void removeListener(int objectId, int selector, long tag){
        try (Arena a = Arena.ofConfined()) {
            int ignored = (int) AudioObjectRemovePropertyListener.invokeExact(objectId, propertyAddress(a, selector, SCOPE_GLOBAL),
                    listenerStub(), MemorySegment.ofAddress(tag));
        } catch (Throwable t) {
            log("AudioObjectRemovePropertyListener failed", t);
        }
    }

    private MemorySegment listenerStub(){
        synchronized (lock) {
            if (listenerStub == null) {
                try {
                    MethodHandle target = MethodHandles.lookup().findVirtual(MacOS_CoreAudio.class, "onPropertyChanged",
                            PROPERTY_LISTENER.toMethodType()).bindTo(this);
                    // global: Core Audio may still be inside a callback while a listener is being removed
                    listenerStub = LINKER.upcallStub(target, PROPERTY_LISTENER, Arena.global());
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
            return listenerStub;
        }
    }

    /** Core Audio notification thread: never blocks or locks, only enqueues. */
    int onPropertyChanged(int objectId, int count, MemorySegment addresses, MemorySegment clientData){
        try {
            long tag = clientData.address();
            if (tag == 0) control.execute(this::defaultOutputChanged);
            else control.execute(() -> deviceChanged(tag));
        } catch (Throwable t) {
            log("Core Audio property listener failed", t);
        }
        return 0;
    }

    private void defaultOutputChanged(){
        synchronized (lock) {
            NativeCaptureDevice d = devices.get(DEFAULT_KEY);
            if (!(d instanceof MacOS_ProcessTapDevice tap) || !tap.isActive()) return;
            if (defaultOutputDevice() == tap.boundDevice) return;
            rebuild(tap);
        }
    }

    private void deviceChanged(long id){
        synchronized (lock) {
            MacOS_ProcessTapDevice tap = tapsById.get(id);
            // its sample rate changed or it went away; reopening fails (→ inactive) for a device that is gone,
            // while the default device simply moves to the new default
            if (tap != null && tap.isActive()) rebuild(tap);
        }
    }

    private static void rebuild(MacOS_ProcessTapDevice tap){
        tap.closeNative();
        try {
            tap.openNative();
        } catch (RuntimeException e) {
            tap.markInactive(String.valueOf(e.getMessage()));
        }
    }
}
