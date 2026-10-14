package com.EonCuber.audio.platforms;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.nio.ByteOrder;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.EonCuber.audio.platforms.MacBindings.*;
import static java.lang.foreign.ValueLayout.*;

/**
 * macOS 13.0–14.1 fallback: the whole system output mix through ScreenCaptureKit (an audio-only SCStream with a
 * 2×2 video stream kept as cheap as possible).
 * <p>
 * Limits: SCK only gives a stereo mixdown, only at 8/16/24/48 kHz (48 kHz is used), in chunks of its own choosing
 * (not tunable, typically longer than the ~3 ms targeted elsewhere). It needs the Screen Recording permission.
 * Since it captures what the system plays regardless of the output device, it follows default-device changes on its
 * own.
 */
final class MacOS_ScreenCaptureKitDevice extends NativeCaptureDevice {
    static final int SAMPLE_RATE = 48000;
    private static final long SCStreamOutputTypeScreen = 0, SCStreamOutputTypeAudio = 1;
    private static final String OUTPUT_CLASS = "AvisSCKAudioOutput";

    /** ScreenCaptureKit / CoreMedia symbols, loaded only when this path is used. */
    static final class Sck {
        private Sck(){}
        static final SymbolLookup SCREEN_CAPTURE_KIT = lib("/System/Library/Frameworks/ScreenCaptureKit.framework/ScreenCaptureKit");
        static final SymbolLookup CORE_MEDIA = lib("/System/Library/Frameworks/CoreMedia.framework/CoreMedia");
        static final MethodHandle CMSampleBufferGetFormatDescription = fn(CORE_MEDIA, "CMSampleBufferGetFormatDescription",
                FunctionDescriptor.of(ADDRESS, ADDRESS));
        static final MethodHandle CMAudioFormatDescriptionGetStreamBasicDescription = fn(CORE_MEDIA,
                "CMAudioFormatDescriptionGetStreamBasicDescription", FunctionDescriptor.of(ADDRESS, ADDRESS));
        static final MethodHandle CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer = fn(CORE_MEDIA,
                "CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
        static final int kCMSampleBufferFlag_AudioBufferList_Assure16ByteAlignment = 1;
        /** CMTime {int64 value; int32 timescale; uint32 flags; int64 epoch} */
        static final StructLayout CMTIME = MemoryLayout.structLayout(JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_LONG);
        /** void f(id, SEL, CMTime) */
        static final MethodHandle MSG_V_CMTIME = LINKER.downcallHandle(OBJC.findOrThrow("objc_msgSend"),
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, CMTIME));

        /** True when SCK with audio capture (macOS 13+) is present. */
        static boolean available(){
            return cls("SCStream").address() != 0 && cls("SCStreamConfiguration").address() != 0;
        }
    }

    // ---------------------------------------------------------------- blocks & runtime class (process-wide)

    private static volatile MacOS_ScreenCaptureKitDevice current;
    private static volatile CompletableFuture<MemorySegment> contentFuture;
    private static volatile CompletableFuture<Void> startFuture, stopFuture;
    private static MemorySegment contentBlock, startBlock, stopBlock, outputClass; // guarded by class lock

    private static synchronized void ensureRuntime(){
        if (outputClass != null) return;
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            Arena g = Arena.global();
            contentBlock = globalBlock(LINKER.upcallStub(l.findStatic(MacOS_ScreenCaptureKitDevice.class, "onContent",
                    FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS).toMethodType()), FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS), g));
            FunctionDescriptor errorOnly = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS);
            startBlock = globalBlock(LINKER.upcallStub(l.findStatic(MacOS_ScreenCaptureKitDevice.class, "onStarted",
                    errorOnly.toMethodType()), errorOnly, g));
            stopBlock = globalBlock(LINKER.upcallStub(l.findStatic(MacOS_ScreenCaptureKitDevice.class, "onStopped",
                    errorOnly.toMethodType()), errorOnly, g));

            // @interface AvisSCKAudioOutput : NSObject <SCStreamOutput>
            MemorySegment cls = cls(OUTPUT_CLASS);
            if (cls.address() == 0) {
                FunctionDescriptor handler = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG);
                MemorySegment imp = LINKER.upcallStub(l.findStatic(MacOS_ScreenCaptureKitDevice.class, "onSampleBuffer",
                        handler.toMethodType()), handler, g);
                cls = (MemorySegment) objc_allocateClassPair.invokeExact(cls("NSObject"), g.allocateFrom(OUTPUT_CLASS), 0L);
                if (cls.address() == 0) throw new IllegalStateException("objc_allocateClassPair failed");
                byte ok = (byte) class_addMethod.invokeExact(cls, sel("stream:didOutputSampleBuffer:ofType:"), imp,
                        g.allocateFrom("v@:@^{opaqueCMSampleBuffer=}q"));
                if (ok == 0) throw new IllegalStateException("class_addMethod failed");
                MemorySegment protocol = (MemorySegment) objc_getProtocol.invokeExact(g.allocateFrom("SCStreamOutput"));
                if (protocol.address() != 0) {
                    byte ignored = (byte) class_addProtocol.invokeExact(cls, protocol);
                }
                objc_registerClassPair.invokeExact(cls);
            }
            outputClass = cls;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    /** A global (never copied, never freed) block literal: {isa, flags, reserved, invoke, descriptor}. */
    private static MemorySegment globalBlock(MemorySegment invoke){
        Arena g = Arena.global();
        MemorySegment descriptor = g.allocate(16, 8);
        descriptor.set(JAVA_LONG, 0, 0L);  // reserved
        descriptor.set(JAVA_LONG, 8, 32L); // sizeof(block literal)
        MemorySegment block = g.allocate(32, 8);
        block.set(ADDRESS, 0, NSConcreteGlobalBlock);
        block.set(JAVA_INT, 8, 1 << 28); // BLOCK_IS_GLOBAL
        block.set(JAVA_INT, 12, 0);
        block.set(ADDRESS, 16, invoke);
        block.set(ADDRESS, 24, descriptor);
        return block;
    }

    // ---------------------------------------------------------------- instance

    // guarded by the platform lock
    private MemorySegment stream = MemorySegment.NULL, output = MemorySegment.NULL, queue = MemorySegment.NULL;

    // sample handler queue only
    private final MemorySegment ablBuffer = Arena.ofAuto().allocate(8 + 16L * 16, 16);
    private final MemorySegment blockBufferOut = Arena.ofAuto().allocate(ADDRESS);
    private final MemorySegment[] planes = new MemorySegment[16];

    MacOS_ScreenCaptureKitDevice(Object lock){
        super(lock, "System audio (ScreenCaptureKit)",
                new NativeFormat(SAMPLE_RATE, SampleFormat.F32, ByteOrder.nativeOrder(),
                        new Speaker[]{Speaker.FL, Speaker.FR}));
    }

    @Override
    protected void openNative(){
        ensureRuntime();
        MemorySegment pool = poolPush();
        MemorySegment content = MemorySegment.NULL, filter = MemorySegment.NULL, config = MemorySegment.NULL;
        try (Arena a = Arena.ofConfined()) {
            // shareable content (needs the Screen Recording permission)
            CompletableFuture<MemorySegment> cf = new CompletableFuture<>();
            contentFuture = cf;
            MSG_V_P.invokeExact(cls("SCShareableContent"), sel("getShareableContentWithCompletionHandler:"), contentBlock);
            content = cf.get(10, TimeUnit.SECONDS);
            MemorySegment displays = (MemorySegment) MSG_P.invokeExact(content, sel("displays"));
            if ((long) MSG_L.invokeExact(displays, sel("count")) == 0) throw new IllegalStateException("ScreenCaptureKit reports no displays");
            MemorySegment display = (MemorySegment) MSG_P_L.invokeExact(displays, sel("objectAtIndex:"), 0L);
            MemorySegment empty = (MemorySegment) MSG_P.invokeExact(cls("NSArray"), sel("array"));
            filter = (MemorySegment) MSG_P.invokeExact(cls("SCContentFilter"), sel("alloc"));
            filter = (MemorySegment) MSG_P_PP.invokeExact(filter, sel("initWithDisplay:excludingWindows:"), display, empty);
            // a nil argument further down would raise an ObjC exception, which aborts the JVM
            if (filter.address() == 0 || display.address() == 0) throw new IllegalStateException("SCContentFilter init failed");

            config = (MemorySegment) MSG_P.invokeExact(cls("SCStreamConfiguration"), sel("new"));
            if (config.address() == 0) throw new IllegalStateException("SCStreamConfiguration init failed");
            MSG_V_B.invokeExact(config, sel("setCapturesAudio:"), (byte) 1);
            MSG_V_B.invokeExact(config, sel("setExcludesCurrentProcessAudio:"), (byte) 1);
            MSG_V_L.invokeExact(config, sel("setSampleRate:"), (long) SAMPLE_RATE);
            MSG_V_L.invokeExact(config, sel("setChannelCount:"), 2L);
            MSG_V_L.invokeExact(config, sel("setWidth:"), 2L);
            MSG_V_L.invokeExact(config, sel("setHeight:"), 2L);
            MSG_V_B.invokeExact(config, sel("setShowsCursor:"), (byte) 0);
            MemorySegment interval = a.allocate(Sck.CMTIME);
            interval.set(JAVA_LONG, 0, 1L); // 1 frame per second
            interval.set(JAVA_INT, 8, 1);
            interval.set(JAVA_INT, 12, 1);  // kCMTimeFlags_Valid
            interval.set(JAVA_LONG, 16, 0L);
            Sck.MSG_V_CMTIME.invokeExact(config, sel("setMinimumFrameInterval:"), interval);

            MemorySegment s = (MemorySegment) MSG_P.invokeExact(cls("SCStream"), sel("alloc"));
            stream = (MemorySegment) MSG_P_PPP.invokeExact(s, sel("initWithFilter:configuration:delegate:"), filter, config, MemorySegment.NULL);
            if (stream.address() == 0) throw new IllegalStateException("SCStream init failed");
            output = (MemorySegment) MSG_P.invokeExact(outputClass, sel("alloc"));
            output = (MemorySegment) MSG_P.invokeExact(output, sel("init"));
            queue = (MemorySegment) dispatch_queue_create.invokeExact(a.allocateFrom("com.EonCuber.avis.sck"), MemorySegment.NULL);
            MemorySegment error = a.allocate(ADDRESS);
            for (long type : new long[]{SCStreamOutputTypeAudio, SCStreamOutputTypeScreen}) {
                byte ok = (byte) MSG_B_PLPP.invokeExact(stream, sel("addStreamOutput:type:sampleHandlerQueue:error:"), output, type, queue, error);
                if (ok == 0 && type == SCStreamOutputTypeAudio)
                    throw new IllegalStateException("addStreamOutput failed: " + describeError(error.get(ADDRESS, 0)));
            }

            current = this;
            CompletableFuture<Void> started = new CompletableFuture<>();
            startFuture = started;
            MSG_V_P.invokeExact(stream, sel("startCaptureWithCompletionHandler:"), startBlock);
            started.get(10, TimeUnit.SECONDS);
        } catch (Throwable t) {
            closeNative();
            Throwable cause = t instanceof java.util.concurrent.ExecutionException ? t.getCause() : t;
            if (cause instanceof IllegalStateException ise) throw ise;
            throw new IllegalStateException("Could not start ScreenCaptureKit audio capture", cause);
        } finally {
            release(content);
            release(filter);
            release(config);
            poolPop(pool);
        }
    }

    @Override
    protected void closeNative(){
        MemorySegment pool = poolPush();
        try {
            if (stream.address() != 0) {
                CompletableFuture<Void> stopped = new CompletableFuture<>();
                stopFuture = stopped;
                MSG_V_P.invokeExact(stream, sel("stopCaptureWithCompletionHandler:"), stopBlock);
                try {
                    stopped.get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    log("ScreenCaptureKit stop did not complete cleanly", e);
                }
            }
        } catch (Throwable t) {
            log("Error while stopping ScreenCaptureKit", t);
        } finally {
            if (current == this) current = null;
            release(stream);
            release(output);
            if (queue.address() != 0) {
                try {
                    dispatch_release.invokeExact(queue);
                } catch (Throwable t) {
                    log("dispatch_release failed", t);
                }
            }
            stream = output = queue = MemorySegment.NULL;
            poolPop(pool);
        }
    }

    // ---------------------------------------------------------------- callbacks (arbitrary GCD threads)

    static void onContent(MemorySegment block, MemorySegment content, MemorySegment error){
        CompletableFuture<MemorySegment> f = contentFuture;
        try {
            if (f == null) return;
            if (content.address() == 0) {
                f.completeExceptionally(new IllegalStateException(
                        "ScreenCaptureKit refused access (grant Screen Recording permission): " + describeError(error)));
            } else {
                f.complete((MemorySegment) MSG_P.invokeExact(content, sel("retain")));
            }
        } catch (Throwable t) {
            if (f != null) f.completeExceptionally(t);
        }
    }

    static void onStarted(MemorySegment block, MemorySegment error){
        complete(startFuture, error, "start");
    }

    static void onStopped(MemorySegment block, MemorySegment error){
        complete(stopFuture, error, "stop");
    }

    private static void complete(CompletableFuture<Void> f, MemorySegment error, String what){
        if (f == null) return;
        try {
            if (error.address() == 0) f.complete(null);
            else f.completeExceptionally(new IllegalStateException("ScreenCaptureKit " + what + " failed: " + describeError(error)));
        } catch (Throwable t) {
            f.completeExceptionally(t);
        }
    }

    /** -[SCStreamOutput stream:didOutputSampleBuffer:ofType:] on the sample handler queue. */
    static void onSampleBuffer(MemorySegment self, MemorySegment cmd, MemorySegment stream, MemorySegment sampleBuffer, long type){
        if (type != SCStreamOutputTypeAudio) return;
        MacOS_ScreenCaptureKitDevice d = current;
        if (d == null || sampleBuffer.address() == 0) return;
        try {
            d.onAudio(sampleBuffer);
        } catch (Throwable t) {
            log("ScreenCaptureKit sample handler failed", t);
        }
    }

    private void onAudio(MemorySegment sampleBuffer) throws Throwable {
        MemorySegment fd = (MemorySegment) Sck.CMSampleBufferGetFormatDescription.invokeExact(sampleBuffer);
        if (fd.address() == 0) return;
        MemorySegment asbdPtr = (MemorySegment) Sck.CMAudioFormatDescriptionGetStreamBasicDescription.invokeExact(fd);
        if (asbdPtr.address() == 0) return;
        Asbd asbd = Asbd.read(asbdPtr);
        SampleFormat sf = asbd.sampleFormat();
        if (sf == null || asbd.channels() <= 0 || asbd.channels() > planes.length) return;
        NativeFormat f = new NativeFormat((int) Math.round(asbd.sampleRate()), sf, asbd.order(), defaultOrder(asbd.channels()));
        if (!f.sameAs(state().format())) publish(deviceName(), f);

        int st = (int) Sck.CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer.invokeExact(sampleBuffer, MemorySegment.NULL,
                ablBuffer, ablBuffer.byteSize(), MemorySegment.NULL, MemorySegment.NULL,
                Sck.kCMSampleBufferFlag_AudioBufferList_Assure16ByteAlignment, blockBufferOut);
        if (st != 0) return;
        MemorySegment blockBuffer = blockBufferOut.get(ADDRESS, 0);
        try {
            deliverBufferList(this, ablBuffer, asbd.channels(), asbd.nonInterleaved(), sf.bytes, false, planes);
        } finally {
            cfRelease(blockBuffer);
        }
    }

    private static String describeError(MemorySegment nsError){
        if (nsError == null || nsError.address() == 0) return "unknown error";
        try {
            MemorySegment desc = (MemorySegment) MSG_P.invokeExact(nsError, sel("localizedDescription"));
            return desc.address() == 0 ? "unknown error" : javaString(desc);
        } catch (Throwable t) {
            return "unknown error";
        }
    }
}
