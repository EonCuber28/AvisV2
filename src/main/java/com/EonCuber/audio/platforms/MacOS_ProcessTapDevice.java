package com.EonCuber.audio.platforms;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.EonCuber.audio.platforms.MacBindings.*;
import static java.lang.foreign.ValueLayout.*;

/**
 * macOS 14.2+ capture of one output device through a Core Audio process tap.
 * <p>
 * A {@code CATapDescription} taps stream 0 of the device for all processes ("The format of the tap will match the
 * format of this stream", so capture is 1:1). The tap is wrapped in a private aggregate device whose IO buffer is
 * set to about 3 ms, and audio arrives through an AudioDeviceIOProc on Core Audio's IO thread.
 */
final class MacOS_ProcessTapDevice extends NativeCaptureDevice {
    static final String AGGREGATE_UID_PREFIX = "com.EonCuber.avis.tap.";
    private static final Map<Long, MacOS_ProcessTapDevice> LIVE = new ConcurrentHashMap<>();
    private static volatile MemorySegment ioProcStub;

    final long id;
    final String uid; // null = follow the default output device
    private final MacOS_CoreAudio platform;

    // guarded by the platform lock
    int boundDevice;
    private int tapId, aggregateId;
    private MemorySegment procId = MemorySegment.NULL;
    private boolean rateListener, aliveListener;

    // read on the IO thread
    private volatile int tapChannels, tapSampleBytes;
    private volatile boolean tapNonInterleaved;
    private final MemorySegment[] planes = new MemorySegment[64];

    /** Basic facts about an output device. */
    record DeviceInfo(int id, String uid, String name, int sampleRate, int channels, Speaker[] speakers, boolean running) {
        /** Null if the device has no output streams. */
        static DeviceInfo describe(int deviceId){
            int[] streams = getIntArray(deviceId, kAudioDevicePropertyStreams, SCOPE_OUTPUT);
            if (streams.length == 0) return null;
            String uid = getString(deviceId, kAudioDevicePropertyDeviceUID, SCOPE_GLOBAL);
            if (uid == null) return null;
            String name = getString(deviceId, kAudioObjectPropertyName, SCOPE_GLOBAL);
            int rate = (int) Math.round(getDouble(deviceId, kAudioDevicePropertyNominalSampleRate, SCOPE_GLOBAL, 48000));
            int channels;
            try (Arena a = Arena.ofConfined()) {
                MemorySegment asbd = getProperty(a, streams[0], kAudioStreamPropertyVirtualFormat, SCOPE_GLOBAL);
                channels = asbd != null && asbd.byteSize() >= ASBD_SIZE ? Asbd.read(asbd).channels() : 2;
            }
            if (channels <= 0) return null;
            boolean running = getInt(deviceId, kAudioDevicePropertyDeviceIsRunningSomewhere, SCOPE_GLOBAL, 0) != 0;
            return new DeviceInfo(deviceId, uid, name != null ? name : uid, rate, channels,
                    deviceSpeakers(deviceId, channels), running);
        }

        NativeFormat format(){
            return new NativeFormat(sampleRate, SampleFormat.F32, java.nio.ByteOrder.nativeOrder(), speakers);
        }
    }

    MacOS_ProcessTapDevice(MacOS_CoreAudio platform, Object lock, long id, String uid, String name, DeviceInfo info){
        super(lock, name, info.format());
        this.platform = platform;
        this.id = id;
        this.uid = uid;
    }

    static String displayName(DeviceInfo info, boolean followsDefault){
        return followsDefault ? "Default (" + info.name() + ")" : info.name();
    }

    @Override
    protected void openNative(){
        platform.adopt(this); // re-registers after stop(), so listeners can find this device again
        int device = uid == null ? defaultOutputDevice() : deviceForUID(uid);
        if (device == 0) throw new IllegalStateException(uid == null ? "There is no default output device" : "Output device '" + uid + "' is gone");
        DeviceInfo info = DeviceInfo.describe(device);
        if (info == null) throw new IllegalStateException("Device " + device + " has no output streams");
        List<MemorySegment> cfObjects = new ArrayList<>();
        MemorySegment pool = poolPush();
        try (Arena a = Arena.ofConfined()) {
            boundDevice = device;
            MemorySegment cfUid = cfString(info.uid());
            cfObjects.add(cfUid);

            // 1) tap of every process playing to stream 0 of the device
            MemorySegment empty = (MemorySegment) MSG_P.invokeExact(cls("NSArray"), sel("array"));
            MemorySegment desc = (MemorySegment) MSG_P.invokeExact(cls("CATapDescription"), sel("alloc"));
            desc = (MemorySegment) MSG_P_PPL.invokeExact(desc, sel("initExcludingProcesses:andDeviceUID:withStream:"), empty, cfUid, 0L);
            if (desc.address() == 0) throw new IllegalStateException("CATapDescription init failed");
            MemorySegment tapUuid;
            try {
                MSG_V_B.invokeExact(desc, sel("setPrivate:"), (byte) 1);
                MemorySegment nsuuid = (MemorySegment) MSG_P.invokeExact(desc, sel("UUID"));
                tapUuid = cfString(javaString((MemorySegment) MSG_P.invokeExact(nsuuid, sel("UUIDString"))));
                cfObjects.add(tapUuid);
                MemorySegment outTap = a.allocate(JAVA_INT);
                checkOS((int) AudioHardwareCreateProcessTap.invokeExact(desc, outTap), "AudioHardwareCreateProcessTap");
                tapId = outTap.get(JAVA_INT, 0);
            } finally {
                release(desc);
            }

            // 2) its exact format
            MemorySegment fmt = getProperty(a, tapId, kAudioTapPropertyFormat, SCOPE_GLOBAL);
            if (fmt == null || fmt.byteSize() < ASBD_SIZE) throw new IllegalStateException("Could not read the tap format");
            Asbd asbd = Asbd.read(fmt);
            SampleFormat sf = asbd.sampleFormat();
            if (sf == null) throw new IllegalStateException("Unsupported tap format (flags 0x" + Integer.toHexString(asbd.flags()) + ")");
            tapChannels = asbd.channels();
            tapSampleBytes = sf.bytes;
            tapNonInterleaved = asbd.nonInterleaved();
            publish(displayName(info, uid == null),
                    new NativeFormat((int) Math.round(asbd.sampleRate()), sf, asbd.order(), fitSpeakers(info.speakers(), tapChannels)));

            // 3) private aggregate device: the output device as clock, the tap as input
            MemorySegment subDevice = dictionary(a, cfObjects, new String[]{"uid"}, new MemorySegment[]{cfUid});
            MemorySegment tap = dictionary(a, cfObjects, new String[]{"uid", "drift"}, new MemorySegment[]{tapUuid, kCFBooleanTrue});
            MemorySegment description = dictionary(a, cfObjects,
                    new String[]{"name", "uid", "master", "private", "stacked", "tapautostart", "subdevices", "taps"},
                    new MemorySegment[]{
                            track(cfObjects, cfString("Avis system audio tap")),
                            track(cfObjects, cfString(AGGREGATE_UID_PREFIX + UUID.randomUUID())),
                            cfUid, kCFBooleanTrue, kCFBooleanFalse, kCFBooleanTrue,
                            array(a, cfObjects, subDevice), array(a, cfObjects, tap)});
            MemorySegment outAggregate = a.allocate(JAVA_INT);
            checkOS((int) AudioHardwareCreateAggregateDevice.invokeExact(description, outAggregate), "AudioHardwareCreateAggregateDevice");
            aggregateId = outAggregate.get(JAVA_INT, 0);

            // 4) ~3 ms IO buffer
            setBufferSize(a, targetPeriodFrames((int) Math.round(asbd.sampleRate())));

            // 5) IOProc
            LIVE.put(id, this);
            MemorySegment outProc = a.allocate(ADDRESS);
            checkOS((int) AudioDeviceCreateIOProcID.invokeExact(aggregateId, ioProcStub(), MemorySegment.ofAddress(id), outProc),
                    "AudioDeviceCreateIOProcID");
            procId = outProc.get(ADDRESS, 0);
            checkOS((int) AudioDeviceStart.invokeExact(aggregateId, procId), "AudioDeviceStart");

            // follow rate changes of the device itself, and notice when it disappears
            rateListener = platform.addListener(device, kAudioDevicePropertyNominalSampleRate, id);
            aliveListener = platform.addListener(device, kAudioDevicePropertyDeviceIsAlive, id);
        } catch (Throwable t) {
            closeNative();
            if (t instanceof IllegalStateException ise) throw ise;
            throw new IllegalStateException("Could not tap '" + info.name() + "'", t);
        } finally {
            for (MemorySegment cf : cfObjects) cfRelease(cf);
            poolPop(pool);
        }
    }

    @Override
    protected void closeNative(){
        try {
            if (rateListener) platform.removeListener(boundDevice, kAudioDevicePropertyNominalSampleRate, id);
            rateListener = false;
            if (aliveListener) platform.removeListener(boundDevice, kAudioDevicePropertyDeviceIsAlive, id);
            aliveListener = false;
            if (aggregateId != 0 && procId.address() != 0) {
                int ignored = (int) AudioDeviceStop.invokeExact(aggregateId, procId);
                ignored = (int) AudioDeviceDestroyIOProcID.invokeExact(aggregateId, procId); // waits for the IOProc to return
            }
            procId = MemorySegment.NULL;
            if (aggregateId != 0) {
                int ignored = (int) AudioHardwareDestroyAggregateDevice.invokeExact(aggregateId);
            }
            aggregateId = 0;
            if (tapId != 0) {
                int ignored = (int) AudioHardwareDestroyProcessTap.invokeExact(tapId);
            }
            tapId = 0;
        } catch (Throwable t) {
            log("Error while closing the process tap", t);
        } finally {
            LIVE.remove(id);
        }
    }

    private void setBufferSize(Arena a, int frames){
        MemorySegment range = getProperty(a, aggregateId, kAudioDevicePropertyBufferFrameSizeRange, SCOPE_GLOBAL);
        if (range != null && range.byteSize() >= 16) {
            frames = (int) Math.max(range.get(JAVA_DOUBLE, 0), Math.min(range.get(JAVA_DOUBLE, 8), frames));
        }
        int st = setInt(aggregateId, kAudioDevicePropertyBufferFrameSize, SCOPE_GLOBAL, frames);
        if (st != 0) log("Could not set the tap IO buffer to " + frames + " frames (OSStatus " + fourCCString(st) + ")", null);
    }

    private static MemorySegment track(List<MemorySegment> cfObjects, MemorySegment cf){
        cfObjects.add(cf);
        return cf;
    }

    private static MemorySegment dictionary(Arena a, List<MemorySegment> cfObjects, String[] keys, MemorySegment[] values) throws Throwable {
        MemorySegment k = a.allocate(ADDRESS, keys.length);
        MemorySegment v = a.allocate(ADDRESS, values.length);
        for (int i = 0; i < keys.length; i++) {
            k.setAtIndex(ADDRESS, i, track(cfObjects, cfString(keys[i])));
            v.setAtIndex(ADDRESS, i, values[i]);
        }
        MemorySegment dict = (MemorySegment) CFDictionaryCreate.invokeExact(MemorySegment.NULL, k, v, (long) keys.length,
                kCFTypeDictionaryKeyCallBacks, kCFTypeDictionaryValueCallBacks);
        if (dict.address() == 0) throw new IllegalStateException("CFDictionaryCreate failed");
        return track(cfObjects, dict);
    }

    private static MemorySegment array(Arena a, List<MemorySegment> cfObjects, MemorySegment... values) throws Throwable {
        MemorySegment v = a.allocate(ADDRESS, values.length);
        for (int i = 0; i < values.length; i++) v.setAtIndex(ADDRESS, i, values[i]);
        MemorySegment arr = (MemorySegment) CFArrayCreate.invokeExact(MemorySegment.NULL, v, (long) values.length, kCFTypeArrayCallBacks);
        if (arr.address() == 0) throw new IllegalStateException("CFArrayCreate failed");
        return track(cfObjects, arr);
    }

    // ---------------------------------------------------------------- IO thread

    private static MemorySegment ioProcStub(){
        MemorySegment s = ioProcStub;
        if (s != null) return s;
        synchronized (LIVE) {
            if (ioProcStub == null) {
                try {
                    var target = MethodHandles.lookup().findStatic(MacOS_ProcessTapDevice.class, "ioProc",
                            MethodType.methodType(int.class, int.class, MemorySegment.class, MemorySegment.class,
                                    MemorySegment.class, MemorySegment.class, MemorySegment.class, MemorySegment.class));
                    ioProcStub = LINKER.upcallStub(target, IO_PROC, Arena.global());
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
            return ioProcStub;
        }
    }

    static int ioProc(int device, MemorySegment now, MemorySegment input, MemorySegment inputTime,
                      MemorySegment output, MemorySegment outputTime, MemorySegment clientData){
        try {
            MacOS_ProcessTapDevice d = LIVE.get(clientData.address());
            if (d != null)
                deliverBufferList(d, input, d.tapChannels, d.tapNonInterleaved, d.tapSampleBytes, true, d.planes);
        } catch (Throwable t) {
            log("Process tap IOProc failed", t);
        }
        return 0;
    }
}
