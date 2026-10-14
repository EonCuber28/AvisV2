package com.EonCuber.audio.platforms;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.EonCuber.audio.platforms.NativeCaptureDevice.*;
import static java.lang.foreign.ValueLayout.*;

/**
 * CoreAudio / CoreFoundation / Objective-C runtime bindings (checked against the macOS 15.5 SDK headers).
 * Loaded lazily: only touched from {@link MacOS_CoreAudio} and its devices.
 * <p>
 * Libraries are opened by path string ({@code dlopen}), which also finds system frameworks that live only in the
 * dyld shared cache. {@code objc_msgSend} gets one downcall handle per exact signature, which arm64 requires.
 */
final class MacBindings {
    private MacBindings(){}

    static final Linker LINKER = Linker.nativeLinker();
    static final SymbolLookup CORE_AUDIO = lib("/System/Library/Frameworks/CoreAudio.framework/CoreAudio");
    static final SymbolLookup CORE_FOUNDATION = lib("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation");
    static final SymbolLookup FOUNDATION = lib("/System/Library/Frameworks/Foundation.framework/Foundation"); // registers NSArray & co.
    static final SymbolLookup OBJC = lib("/usr/lib/libobjc.A.dylib");
    static final SymbolLookup SYSTEM = lib("/usr/lib/libSystem.B.dylib");

    static SymbolLookup lib(String path){
        return SymbolLookup.libraryLookup(path, Arena.global());
    }

    // ---------------------------------------------------------------- CoreAudio constants
    static final int kAudioObjectSystemObject = 1;
    static final int kAudioObjectPropertyElementMain = 0;
    static final int SCOPE_GLOBAL = fourCC("glob"), SCOPE_OUTPUT = fourCC("outp");
    static final int kAudioHardwarePropertyDevices = fourCC("dev#");
    static final int kAudioHardwarePropertyDefaultOutputDevice = fourCC("dOut");
    static final int kAudioHardwarePropertyTranslateUIDToDevice = fourCC("uidd");
    static final int kAudioObjectPropertyName = fourCC("lnam");
    static final int kAudioDevicePropertyDeviceUID = fourCC("uid ");
    static final int kAudioDevicePropertyStreams = fourCC("stm#");
    static final int kAudioDevicePropertyNominalSampleRate = fourCC("nsrt");
    static final int kAudioDevicePropertyPreferredChannelLayout = fourCC("srnd");
    static final int kAudioDevicePropertyDeviceIsRunningSomewhere = fourCC("gone");
    static final int kAudioDevicePropertyDeviceIsAlive = fourCC("livn");
    static final int kAudioDevicePropertyBufferFrameSize = fourCC("fsiz");
    static final int kAudioDevicePropertyBufferFrameSizeRange = fourCC("fsz#");
    static final int kAudioStreamPropertyVirtualFormat = fourCC("sfmt");
    static final int kAudioTapPropertyFormat = fourCC("tfmt");
    static final int kAudioFormatLinearPCM = fourCC("lpcm");
    static final int kAudioFormatFlagIsFloat = 0x1, kAudioFormatFlagIsBigEndian = 0x2, kAudioFormatFlagIsSignedInteger = 0x4,
            kAudioFormatFlagIsPacked = 0x8, kAudioFormatFlagIsAlignedHigh = 0x10, kAudioFormatFlagIsNonInterleaved = 0x20;
    static final int kAudioChannelLayoutTag_UseChannelDescriptions = 0, kAudioChannelLayoutTag_UseChannelBitmap = 1 << 16;
    static final int kCFStringEncodingUTF8 = 0x08000100;
    /** AudioStreamBasicDescription: 40 bytes. */
    static final long ASBD_SIZE = 40;
    /** AudioObjectPropertyAddress {selector, scope, element}. */
    static final long PROPERTY_ADDRESS_SIZE = 12;

    // ---------------------------------------------------------------- CoreAudio functions
    static final MethodHandle AudioObjectGetPropertyDataSize = fn(CORE_AUDIO, "AudioObjectGetPropertyDataSize",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    static final MethodHandle AudioObjectGetPropertyData = fn(CORE_AUDIO, "AudioObjectGetPropertyData",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle AudioObjectSetPropertyData = fn(CORE_AUDIO, "AudioObjectSetPropertyData",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
    static final MethodHandle AudioObjectAddPropertyListener = fn(CORE_AUDIO, "AudioObjectAddPropertyListener",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle AudioObjectRemovePropertyListener = fn(CORE_AUDIO, "AudioObjectRemovePropertyListener",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle AudioHardwareCreateAggregateDevice = fn(CORE_AUDIO, "AudioHardwareCreateAggregateDevice",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    static final MethodHandle AudioHardwareDestroyAggregateDevice = fn(CORE_AUDIO, "AudioHardwareDestroyAggregateDevice",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    static final MethodHandle AudioDeviceCreateIOProcID = fn(CORE_AUDIO, "AudioDeviceCreateIOProcID",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle AudioDeviceDestroyIOProcID = fn(CORE_AUDIO, "AudioDeviceDestroyIOProcID",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));
    static final MethodHandle AudioDeviceStart = fn(CORE_AUDIO, "AudioDeviceStart", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));
    static final MethodHandle AudioDeviceStop = fn(CORE_AUDIO, "AudioDeviceStop", FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));
    /** macOS 14.2+ only; null on older systems. */
    static final MethodHandle AudioHardwareCreateProcessTap = optionalFn(CORE_AUDIO, "AudioHardwareCreateProcessTap",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    static final MethodHandle AudioHardwareDestroyProcessTap = optionalFn(CORE_AUDIO, "AudioHardwareDestroyProcessTap",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT));

    /** OSStatus (*AudioObjectPropertyListenerProc)(AudioObjectID, UInt32, const AudioObjectPropertyAddress*, void*) */
    static final FunctionDescriptor PROPERTY_LISTENER = FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS);
    /** OSStatus (*AudioDeviceIOProc)(AudioObjectID, const AudioTimeStamp*, const AudioBufferList*, const AudioTimeStamp*,
     *  AudioBufferList*, const AudioTimeStamp*, void*) */
    static final FunctionDescriptor IO_PROC = FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS);

    // ---------------------------------------------------------------- CoreFoundation
    static final MethodHandle CFRelease = fn(CORE_FOUNDATION, "CFRelease", FunctionDescriptor.ofVoid(ADDRESS));
    static final MethodHandle CFStringCreateWithCString = fn(CORE_FOUNDATION, "CFStringCreateWithCString",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
    static final MethodHandle CFStringGetLength = fn(CORE_FOUNDATION, "CFStringGetLength", FunctionDescriptor.of(JAVA_LONG, ADDRESS));
    static final MethodHandle CFStringGetMaximumSizeForEncoding = fn(CORE_FOUNDATION, "CFStringGetMaximumSizeForEncoding",
            FunctionDescriptor.of(JAVA_LONG, JAVA_LONG, JAVA_INT));
    static final MethodHandle CFStringGetCString = fn(CORE_FOUNDATION, "CFStringGetCString",
            FunctionDescriptor.of(JAVA_BYTE, ADDRESS, ADDRESS, JAVA_LONG, JAVA_INT));
    static final MethodHandle CFArrayCreate = fn(CORE_FOUNDATION, "CFArrayCreate",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS));
    static final MethodHandle CFDictionaryCreate = fn(CORE_FOUNDATION, "CFDictionaryCreate",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS));
    /** Addresses of the callback structs themselves (pass these, not their contents). */
    static final MemorySegment kCFTypeArrayCallBacks = CORE_FOUNDATION.findOrThrow("kCFTypeArrayCallBacks");
    static final MemorySegment kCFTypeDictionaryKeyCallBacks = CORE_FOUNDATION.findOrThrow("kCFTypeDictionaryKeyCallBacks");
    static final MemorySegment kCFTypeDictionaryValueCallBacks = CORE_FOUNDATION.findOrThrow("kCFTypeDictionaryValueCallBacks");
    /** CFBooleanRef values (the symbols are pointer variables). */
    static final MemorySegment kCFBooleanTrue = CORE_FOUNDATION.findOrThrow("kCFBooleanTrue").reinterpret(ADDRESS.byteSize()).get(ADDRESS, 0);
    static final MemorySegment kCFBooleanFalse = CORE_FOUNDATION.findOrThrow("kCFBooleanFalse").reinterpret(ADDRESS.byteSize()).get(ADDRESS, 0);

    // ---------------------------------------------------------------- Objective-C runtime
    static final MethodHandle objc_getClass = fn(OBJC, "objc_getClass", FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MethodHandle objc_getProtocol = fn(OBJC, "objc_getProtocol", FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MethodHandle sel_registerName = fn(OBJC, "sel_registerName", FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MethodHandle objc_allocateClassPair = fn(OBJC, "objc_allocateClassPair",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));
    static final MethodHandle objc_registerClassPair = fn(OBJC, "objc_registerClassPair", FunctionDescriptor.ofVoid(ADDRESS));
    static final MethodHandle class_addMethod = fn(OBJC, "class_addMethod", FunctionDescriptor.of(JAVA_BYTE, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle class_addProtocol = fn(OBJC, "class_addProtocol", FunctionDescriptor.of(JAVA_BYTE, ADDRESS, ADDRESS));
    static final MethodHandle objc_autoreleasePoolPush = fn(OBJC, "objc_autoreleasePoolPush", FunctionDescriptor.of(ADDRESS));
    static final MethodHandle objc_autoreleasePoolPop = fn(OBJC, "objc_autoreleasePoolPop", FunctionDescriptor.ofVoid(ADDRESS));

    private static final MemorySegment MSG = OBJC.findOrThrow("objc_msgSend");
    /** id f(id, SEL) */
    static final MethodHandle MSG_P = msg(FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    /** id f(id, SEL, id) */
    static final MethodHandle MSG_P_P = msg(FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    /** id f(id, SEL, id, id) */
    static final MethodHandle MSG_P_PP = msg(FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    /** id f(id, SEL, id, id, id) */
    static final MethodHandle MSG_P_PPP = msg(FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    /** id f(id, SEL, id, id, NSInteger) */
    static final MethodHandle MSG_P_PPL = msg(FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));
    /** id f(id, SEL, NSUInteger) */
    static final MethodHandle MSG_P_L = msg(FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));
    /** NSUInteger f(id, SEL) */
    static final MethodHandle MSG_L = msg(FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS));
    /** void f(id, SEL) */
    static final MethodHandle MSG_V = msg(FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    /** void f(id, SEL, BOOL) */
    static final MethodHandle MSG_V_B = msg(FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_BYTE));
    /** void f(id, SEL, NSInteger) */
    static final MethodHandle MSG_V_L = msg(FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_LONG));
    /** void f(id, SEL, id) */
    static final MethodHandle MSG_V_P = msg(FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
    /** BOOL f(id, SEL, id, NSInteger, id, NSError**) */
    static final MethodHandle MSG_B_PLPP = msg(FunctionDescriptor.of(JAVA_BYTE, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS));

    // ---------------------------------------------------------------- libSystem
    static final MethodHandle dispatch_queue_create = fn(SYSTEM, "dispatch_queue_create", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle dispatch_release = fn(SYSTEM, "dispatch_release", FunctionDescriptor.ofVoid(ADDRESS));
    static final MemorySegment NSConcreteGlobalBlock = SYSTEM.findOrThrow("_NSConcreteGlobalBlock");

    // ---------------------------------------------------------------- helpers

    static MethodHandle fn(SymbolLookup lib, String name, FunctionDescriptor fd){
        return LINKER.downcallHandle(lib.findOrThrow(name), fd);
    }

    static MethodHandle optionalFn(SymbolLookup lib, String name, FunctionDescriptor fd){
        return lib.find(name).map(addr -> LINKER.downcallHandle(addr, fd)).orElse(null);
    }

    private static MethodHandle msg(FunctionDescriptor fd){
        return LINKER.downcallHandle(MSG, fd);
    }

    static int fourCC(String s){
        return (s.charAt(0) << 24) | (s.charAt(1) << 16) | (s.charAt(2) << 8) | s.charAt(3);
    }

    static String fourCCString(int code){
        char[] c = {(char) ((code >>> 24) & 0xFF), (char) ((code >>> 16) & 0xFF), (char) ((code >>> 8) & 0xFF), (char) (code & 0xFF)};
        for (char ch : c) if (ch < 32 || ch > 126) return Integer.toString(code);
        return "'" + new String(c) + "'";
    }

    static void checkOS(int status, String what){
        if (status != 0) throw new IllegalStateException(what + " failed (OSStatus " + fourCCString(status) + ")");
    }

    private static final Map<String, MemorySegment> SELECTORS = new ConcurrentHashMap<>();
    private static final Map<String, MemorySegment> CLASSES = new ConcurrentHashMap<>();

    static MemorySegment sel(String name){
        return SELECTORS.computeIfAbsent(name, n -> {
            try (Arena a = Arena.ofConfined()) {
                return (MemorySegment) sel_registerName.invokeExact(a.allocateFrom(n));
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        });
    }

    /** The class, or a NULL segment when it doesn't exist (not cached in that case). */
    static MemorySegment cls(String name){
        MemorySegment c = CLASSES.get(name);
        if (c != null) return c;
        try (Arena a = Arena.ofConfined()) {
            c = (MemorySegment) objc_getClass.invokeExact(a.allocateFrom(name));
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
        if (c.address() != 0) CLASSES.put(name, c);
        return c;
    }

    static MemorySegment poolPush(){
        try {
            return (MemorySegment) objc_autoreleasePoolPush.invokeExact();
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    static void poolPop(MemorySegment pool){
        try {
            objc_autoreleasePoolPop.invokeExact(pool);
        } catch (Throwable t) {
            log("objc_autoreleasePoolPop failed", t);
        }
    }

    static void release(MemorySegment obj){
        if (obj == null || obj.address() == 0) return;
        try {
            MSG_V.invokeExact(obj, sel("release"));
        } catch (Throwable t) {
            log("-release failed", t);
        }
    }

    static void cfRelease(MemorySegment cf){
        if (cf == null || cf.address() == 0) return;
        try {
            CFRelease.invokeExact(cf);
        } catch (Throwable t) {
            log("CFRelease failed", t);
        }
    }

    /** +1 CFStringRef (toll-free bridged to NSString). Caller releases. */
    static MemorySegment cfString(String s){
        try (Arena a = Arena.ofConfined()) {
            MemorySegment str = (MemorySegment) CFStringCreateWithCString.invokeExact(MemorySegment.NULL, a.allocateFrom(s), kCFStringEncodingUTF8);
            if (str.address() == 0) throw new IllegalStateException("CFStringCreateWithCString failed");
            return str;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    static String javaString(MemorySegment cfString){
        if (cfString.address() == 0) return null;
        try (Arena a = Arena.ofConfined()) {
            long len = (long) CFStringGetLength.invokeExact(cfString);
            long max = (long) CFStringGetMaximumSizeForEncoding.invokeExact(len, kCFStringEncodingUTF8) + 1;
            MemorySegment buf = a.allocate(max);
            if ((byte) CFStringGetCString.invokeExact(cfString, buf, max, kCFStringEncodingUTF8) == 0) return null;
            return buf.getString(0);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    // ---------------------------------------------------------------- AudioObject property helpers

    static MemorySegment propertyAddress(Arena a, int selector, int scope){
        MemorySegment addr = a.allocate(PROPERTY_ADDRESS_SIZE, 4);
        addr.set(JAVA_INT, 0, selector);
        addr.set(JAVA_INT, 4, scope);
        addr.set(JAVA_INT, 8, kAudioObjectPropertyElementMain);
        return addr;
    }

    /** Raw property bytes, or null if the property doesn't exist / fails. */
    static MemorySegment getProperty(Arena a, int objectId, int selector, int scope){
        try {
            MemorySegment addr = propertyAddress(a, selector, scope);
            MemorySegment size = a.allocate(JAVA_INT);
            if ((int) AudioObjectGetPropertyDataSize.invokeExact(objectId, addr, 0, MemorySegment.NULL, size) != 0) return null;
            int n = size.get(JAVA_INT, 0);
            MemorySegment data = a.allocate(Math.max(n, 1), 8);
            if ((int) AudioObjectGetPropertyData.invokeExact(objectId, addr, 0, MemorySegment.NULL, size, data) != 0) return null;
            return data.asSlice(0, size.get(JAVA_INT, 0));
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    static int getInt(int objectId, int selector, int scope, int fallback){
        try (Arena a = Arena.ofConfined()) {
            MemorySegment d = getProperty(a, objectId, selector, scope);
            return d == null || d.byteSize() < 4 ? fallback : d.get(JAVA_INT, 0);
        }
    }

    static double getDouble(int objectId, int selector, int scope, double fallback){
        try (Arena a = Arena.ofConfined()) {
            MemorySegment d = getProperty(a, objectId, selector, scope);
            return d == null || d.byteSize() < 8 ? fallback : d.get(JAVA_DOUBLE, 0);
        }
    }

    static int[] getIntArray(int objectId, int selector, int scope){
        try (Arena a = Arena.ofConfined()) {
            MemorySegment d = getProperty(a, objectId, selector, scope);
            return d == null ? new int[0] : d.toArray(JAVA_INT);
        }
    }

    /** CFString-valued properties (returned +1, released here). */
    static String getString(int objectId, int selector, int scope){
        try (Arena a = Arena.ofConfined()) {
            MemorySegment d = getProperty(a, objectId, selector, scope);
            if (d == null || d.byteSize() < ADDRESS.byteSize()) return null;
            MemorySegment str = d.get(ADDRESS, 0);
            try {
                return javaString(str);
            } finally {
                cfRelease(str);
            }
        }
    }

    static int setInt(int objectId, int selector, int scope, int value){
        try (Arena a = Arena.ofConfined()) {
            MemorySegment v = a.allocate(JAVA_INT);
            v.set(JAVA_INT, 0, value);
            return (int) AudioObjectSetPropertyData.invokeExact(objectId, propertyAddress(a, selector, scope), 0, MemorySegment.NULL, 4, v);
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    static int defaultOutputDevice(){
        return getInt(kAudioObjectSystemObject, kAudioHardwarePropertyDefaultOutputDevice, SCOPE_GLOBAL, 0);
    }

    /** kAudioHardwarePropertyTranslateUIDToDevice; 0 if not found. */
    static int deviceForUID(String uid){
        MemorySegment cf = cfString(uid);
        try (Arena a = Arena.ofConfined()) {
            MemorySegment qualifier = a.allocate(ADDRESS);
            qualifier.set(ADDRESS, 0, cf);
            MemorySegment size = a.allocate(JAVA_INT);
            size.set(JAVA_INT, 0, 4);
            MemorySegment out = a.allocate(JAVA_INT);
            int st = (int) AudioObjectGetPropertyData.invokeExact(kAudioObjectSystemObject,
                    propertyAddress(a, kAudioHardwarePropertyTranslateUIDToDevice, SCOPE_GLOBAL),
                    (int) ADDRESS.byteSize(), qualifier, size, out);
            return st == 0 ? out.get(JAVA_INT, 0) : 0;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        } finally {
            cfRelease(cf);
        }
    }

    // ---------------------------------------------------------------- formats & layouts

    private static final Speaker[] BITMAP_ORDER = {
            Speaker.FL, Speaker.FR, Speaker.FC, Speaker.LFE, Speaker.BL, Speaker.BR, Speaker.FLC, Speaker.FRC,
            Speaker.BC, Speaker.SL, Speaker.SR, Speaker.TC, Speaker.TFL, Speaker.TFC, Speaker.TFR, Speaker.TBL,
            Speaker.TBC, Speaker.TBR
    };

    static Speaker speakerForLabel(int label){
        return switch (label) {
            case 1 -> Speaker.FL;           // Left
            case 2 -> Speaker.FR;           // Right
            case 3 -> Speaker.FC;           // Center
            case 4 -> Speaker.LFE;          // LFEScreen
            case 5 -> Speaker.SL;           // LeftSurround
            case 6 -> Speaker.SR;           // RightSurround
            case 7 -> Speaker.FLC;          // LeftCenter
            case 8 -> Speaker.FRC;          // RightCenter
            case 9, 44 -> Speaker.BC;       // CenterSurround, CenterSurroundDirect
            case 10 -> Speaker.SL;          // LeftSurroundDirect
            case 11 -> Speaker.SR;          // RightSurroundDirect
            case 12 -> Speaker.TC;          // TopCenterSurround
            case 13 -> Speaker.TFL;         // VerticalHeightLeft
            case 14 -> Speaker.TFC;         // VerticalHeightCenter
            case 15 -> Speaker.TFR;         // VerticalHeightRight
            case 16 -> Speaker.TBL;         // TopBackLeft
            case 17 -> Speaker.TBC;         // TopBackCenter
            case 18 -> Speaker.TBR;         // TopBackRight
            case 33 -> Speaker.BL;          // RearSurroundLeft
            case 34 -> Speaker.BR;          // RearSurroundRight
            case 42 -> Speaker.MONO;        // Mono
            default -> Speaker.OTHER;
        };
    }

    /** The device's preferred output channel layout, trimmed / padded to {@code channels}. */
    static Speaker[] deviceSpeakers(int deviceId, int channels){
        Speaker[] layout = null;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment d = getProperty(a, deviceId, kAudioDevicePropertyPreferredChannelLayout, SCOPE_OUTPUT);
            if (d != null && d.byteSize() >= 12) {
                int tag = d.get(JAVA_INT, 0), bitmap = d.get(JAVA_INT, 4), count = d.get(JAVA_INT, 8);
                if (tag == kAudioChannelLayoutTag_UseChannelDescriptions && count > 0 && d.byteSize() >= 12 + 20L * count) {
                    layout = new Speaker[count];
                    for (int i = 0; i < count; i++) layout[i] = speakerForLabel(d.get(JAVA_INT, 12 + 20L * i));
                } else if (tag == kAudioChannelLayoutTag_UseChannelBitmap && bitmap != 0) {
                    java.util.List<Speaker> s = new java.util.ArrayList<>();
                    for (int bit = 0; bit < BITMAP_ORDER.length; bit++) if ((bitmap & (1 << bit)) != 0) s.add(BITMAP_ORDER[bit]);
                    layout = s.toArray(new Speaker[0]);
                }
            }
        }
        return fitSpeakers(layout, channels);
    }

    static Speaker[] fitSpeakers(Speaker[] layout, int channels){
        if (layout == null || layout.length < channels) return defaultOrder(channels);
        if (layout.length == channels) return layout;
        return java.util.Arrays.copyOf(layout, channels);
    }

    /** Parsed AudioStreamBasicDescription. */
    record Asbd(double sampleRate, int formatId, int flags, int bytesPerFrame, int channels, int bitsPerChannel) {
        static Asbd read(MemorySegment p){
            MemorySegment s = p.reinterpret(ASBD_SIZE);
            return new Asbd(s.get(JAVA_DOUBLE, 0), s.get(JAVA_INT, 8), s.get(JAVA_INT, 12), s.get(JAVA_INT, 24),
                    s.get(JAVA_INT, 28), s.get(JAVA_INT, 32));
        }

        boolean nonInterleaved(){ return (flags & kAudioFormatFlagIsNonInterleaved) != 0; }

        /** Bytes per sample, from the frame size (which covers one channel when non-interleaved). */
        int sampleBytes(){
            if (channels <= 0) return 0;
            return nonInterleaved() ? bytesPerFrame : bytesPerFrame / channels;
        }

        /** The sample format, or null if it isn't linear PCM we can read. */
        SampleFormat sampleFormat(){
            if (formatId != kAudioFormatLinearPCM) return null;
            int bytes = sampleBytes();
            if ((flags & kAudioFormatFlagIsFloat) != 0) {
                return bytes == 4 ? SampleFormat.F32 : bytes == 8 ? SampleFormat.F64 : null;
            }
            boolean signed = (flags & kAudioFormatFlagIsSignedInteger) != 0;
            return switch (bytes) {
                case 1 -> signed ? null : SampleFormat.U8;
                case 2 -> SampleFormat.S16;
                case 3 -> SampleFormat.S24_PACKED;
                // 24-in-32 aligned high reads exactly like 32-bit; aligned low would need a shift
                case 4 -> (bitsPerChannel == 32 || (flags & kAudioFormatFlagIsAlignedHigh) != 0) ? SampleFormat.S32 : null;
                default -> null;
            };
        }

        ByteOrder order(){
            return (flags & kAudioFormatFlagIsBigEndian) != 0 ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN;
        }
    }

    /**
     * Delivers an AudioBufferList to a device. {@code fromEnd} takes the trailing buffers (a tap's streams follow any
     * input streams of the aggregate's sub-device).
     */
    static void deliverBufferList(NativeCaptureDevice device, MemorySegment ablPtr, int channels, boolean nonInterleaved,
                                  int sampleBytes, boolean fromEnd, MemorySegment[] planeScratch){
        if (ablPtr.address() == 0 || channels <= 0 || sampleBytes <= 0) return;
        int count = ablPtr.reinterpret(4).get(JAVA_INT, 0);
        MemorySegment abl = ablPtr.reinterpret(8 + 16L * count);
        if (nonInterleaved) {
            if (count < channels) return;
            int first = fromEnd ? count - channels : 0;
            int frames = Integer.MAX_VALUE;
            for (int c = 0; c < channels; c++) {
                long off = 8 + 16L * (first + c);
                int bytes = abl.get(JAVA_INT, off + 4);
                MemorySegment data = abl.get(ADDRESS, off + 8);
                if (data.address() == 0) return;
                planeScratch[c] = data.reinterpret(bytes);
                frames = Math.min(frames, bytes / sampleBytes);
            }
            device.deliverPlanar(planeScratch, frames);
        } else {
            if (count < 1) return;
            long off = 8 + 16L * (fromEnd ? count - 1 : 0);
            int bufferChannels = abl.get(JAVA_INT, off);
            int bytes = abl.get(JAVA_INT, off + 4);
            MemorySegment data = abl.get(ADDRESS, off + 8);
            if (data.address() == 0 || bufferChannels != channels) return;
            device.deliverInterleaved(data.reinterpret(bytes), bytes / (sampleBytes * channels));
        }
    }
}
