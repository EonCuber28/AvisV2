package com.EonCuber.audio.platforms;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.ValueLayout.*;

/**
 * libpulse bindings (pulse/*.h, protocol 35). Loaded lazily: only touched from {@link Unix_PulseAudio}.
 * Struct layouts are validated by {@link MemoryLayout#structLayout} alignment checks at class init.
 */
final class PulseBindings {
    private PulseBindings(){}

    static final Linker LINKER = Linker.nativeLinker();
    static final SymbolLookup LIB = SymbolLookup.libraryLookup("libpulse.so.0", Arena.global());
    static final ValueLayout SIZE_T = (ValueLayout) LINKER.canonicalLayouts().get("size_t");

    // ---------------------------------------------------------------- constants
    static final int PA_SAMPLE_FLOAT32LE = 5, PA_SAMPLE_FLOAT32BE = 6;
    static final int PA_CONTEXT_NOFLAGS = 0;
    static final int PA_CONTEXT_READY = 4, PA_CONTEXT_FAILED = 5, PA_CONTEXT_TERMINATED = 6;
    static final int PA_STREAM_UNCONNECTED = 0, PA_STREAM_CREATING = 1, PA_STREAM_READY = 2, PA_STREAM_FAILED = 3, PA_STREAM_TERMINATED = 4;
    static final int PA_OPERATION_RUNNING = 0;
    static final int PA_STREAM_DONT_MOVE = 0x0200, PA_STREAM_ADJUST_LATENCY = 0x2000;
    static final int PA_SINK_RUNNING = 0, PA_SINK_IDLE = 1;
    static final int PA_SUBSCRIPTION_MASK_SINK = 0x0001, PA_SUBSCRIPTION_MASK_SERVER = 0x0080;
    static final int PA_SUBSCRIPTION_EVENT_SINK = 0x0000, PA_SUBSCRIPTION_EVENT_SERVER = 0x0007;
    static final int PA_SUBSCRIPTION_EVENT_FACILITY_MASK = 0x000F;
    static final int PA_SUBSCRIPTION_EVENT_CHANGE = 0x0010, PA_SUBSCRIPTION_EVENT_TYPE_MASK = 0x0030;
    static final int PA_CHANNELS_MAX = 32;

    // ---------------------------------------------------------------- structs
    static final StructLayout SAMPLE_SPEC = MemoryLayout.structLayout(
            JAVA_INT.withName("format"), JAVA_INT.withName("rate"), JAVA_BYTE.withName("channels"),
            MemoryLayout.paddingLayout(3));
    static final StructLayout CHANNEL_MAP = MemoryLayout.structLayout(
            JAVA_BYTE.withName("channels"), MemoryLayout.paddingLayout(3),
            MemoryLayout.sequenceLayout(PA_CHANNELS_MAX, JAVA_INT).withName("map"));
    static final StructLayout CVOLUME = MemoryLayout.structLayout(
            JAVA_BYTE.withName("channels"), MemoryLayout.paddingLayout(3),
            MemoryLayout.sequenceLayout(PA_CHANNELS_MAX, JAVA_INT).withName("values"));
    static final StructLayout BUFFER_ATTR = MemoryLayout.structLayout(
            JAVA_INT.withName("maxlength"), JAVA_INT.withName("tlength"), JAVA_INT.withName("prebuf"),
            JAVA_INT.withName("minreq"), JAVA_INT.withName("fragsize"));

    /** pa_sink_info, up to and including {@code state}. */
    static final StructLayout SINK_INFO = MemoryLayout.structLayout(
            ADDRESS.withName("name"),
            JAVA_INT.withName("index"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("description"),
            SAMPLE_SPEC.withName("sample_spec"),
            CHANNEL_MAP.withName("channel_map"),
            JAVA_INT.withName("owner_module"),
            CVOLUME.withName("volume"),
            JAVA_INT.withName("mute"),
            JAVA_INT.withName("monitor_source"),
            ADDRESS.withName("monitor_source_name"),
            JAVA_LONG.withName("latency"),
            ADDRESS.withName("driver"),
            JAVA_INT.withName("flags"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("proplist"),
            JAVA_LONG.withName("configured_latency"),
            JAVA_INT.withName("base_volume"),
            JAVA_INT.withName("state"));

    /** pa_server_info, up to and including {@code default_source_name}. */
    static final StructLayout SERVER_INFO = MemoryLayout.structLayout(
            ADDRESS.withName("user_name"), ADDRESS.withName("host_name"),
            ADDRESS.withName("server_version"), ADDRESS.withName("server_name"),
            SAMPLE_SPEC.withName("sample_spec"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("default_sink_name"), ADDRESS.withName("default_source_name"));

    static final long SI_NAME = SINK_INFO.byteOffset(groupElement("name"));
    static final long SI_INDEX = SINK_INFO.byteOffset(groupElement("index"));
    static final long SI_DESCRIPTION = SINK_INFO.byteOffset(groupElement("description"));
    static final long SI_SPEC = SINK_INFO.byteOffset(groupElement("sample_spec"));
    static final long SI_MAP = SINK_INFO.byteOffset(groupElement("channel_map"));
    static final long SI_MONITOR_NAME = SINK_INFO.byteOffset(groupElement("monitor_source_name"));
    static final long SI_STATE = SINK_INFO.byteOffset(groupElement("state"));
    static final long SPEC_FORMAT = SAMPLE_SPEC.byteOffset(groupElement("format"));
    static final long SPEC_RATE = SAMPLE_SPEC.byteOffset(groupElement("rate"));
    static final long SPEC_CHANNELS = SAMPLE_SPEC.byteOffset(groupElement("channels"));
    static final long MAP_CHANNELS = CHANNEL_MAP.byteOffset(groupElement("channels"));
    static final long MAP_MAP = CHANNEL_MAP.byteOffset(groupElement("map"));
    static final long SRV_DEFAULT_SINK = SERVER_INFO.byteOffset(groupElement("default_sink_name"));

    // ---------------------------------------------------------------- callback descriptors
    /** void (*pa_context_notify_cb_t)(pa_context*, void*) */
    static final FunctionDescriptor CONTEXT_NOTIFY_CB = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS);
    /** void (*pa_server_info_cb_t)(pa_context*, const pa_server_info*, void*) */
    static final FunctionDescriptor SERVER_INFO_CB = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS);
    /** void (*pa_sink_info_cb_t)(pa_context*, const pa_sink_info*, int eol, void*) */
    static final FunctionDescriptor SINK_INFO_CB = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT, ADDRESS);
    /** void (*pa_context_subscribe_cb_t)(pa_context*, pa_subscription_event_type_t, uint32_t idx, void*) */
    static final FunctionDescriptor SUBSCRIBE_CB = FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, ADDRESS);
    /** void (*pa_stream_notify_cb_t)(pa_stream*, void*) */
    static final FunctionDescriptor STREAM_NOTIFY_CB = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS);
    /** void (*pa_stream_request_cb_t)(pa_stream*, size_t nbytes, void*) */
    static final FunctionDescriptor STREAM_REQUEST_CB = FunctionDescriptor.ofVoid(ADDRESS, SIZE_T, ADDRESS);

    // ---------------------------------------------------------------- functions
    static final MethodHandle pa_threaded_mainloop_new = fn("pa_threaded_mainloop_new", FunctionDescriptor.of(ADDRESS));
    static final MethodHandle pa_threaded_mainloop_free = fn("pa_threaded_mainloop_free", FunctionDescriptor.ofVoid(ADDRESS));
    static final MethodHandle pa_threaded_mainloop_start = fn("pa_threaded_mainloop_start", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    static final MethodHandle pa_threaded_mainloop_stop = fn("pa_threaded_mainloop_stop", FunctionDescriptor.ofVoid(ADDRESS));
    static final MethodHandle pa_threaded_mainloop_lock = fn("pa_threaded_mainloop_lock", FunctionDescriptor.ofVoid(ADDRESS));
    static final MethodHandle pa_threaded_mainloop_unlock = fn("pa_threaded_mainloop_unlock", FunctionDescriptor.ofVoid(ADDRESS));
    static final MethodHandle pa_threaded_mainloop_wait = fn("pa_threaded_mainloop_wait", FunctionDescriptor.ofVoid(ADDRESS));
    static final MethodHandle pa_threaded_mainloop_signal = fn("pa_threaded_mainloop_signal", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT));
    static final MethodHandle pa_threaded_mainloop_get_api = fn("pa_threaded_mainloop_get_api", FunctionDescriptor.of(ADDRESS, ADDRESS));
    static final MethodHandle pa_threaded_mainloop_in_thread = fn("pa_threaded_mainloop_in_thread", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    static final MethodHandle pa_threaded_mainloop_set_name = fn("pa_threaded_mainloop_set_name", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

    static final MethodHandle pa_context_new = fn("pa_context_new", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle pa_context_unref = fn("pa_context_unref", FunctionDescriptor.ofVoid(ADDRESS));
    static final MethodHandle pa_context_set_state_callback = fn("pa_context_set_state_callback", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle pa_context_connect = fn("pa_context_connect", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
    static final MethodHandle pa_context_disconnect = fn("pa_context_disconnect", FunctionDescriptor.ofVoid(ADDRESS));
    static final MethodHandle pa_context_get_state = fn("pa_context_get_state", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    static final MethodHandle pa_context_errno = fn("pa_context_errno", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    static final MethodHandle pa_strerror = fn("pa_strerror", FunctionDescriptor.of(ADDRESS, JAVA_INT));
    static final MethodHandle pa_context_get_server_info = fn("pa_context_get_server_info", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle pa_context_get_sink_info_by_name = fn("pa_context_get_sink_info_by_name", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle pa_context_get_sink_info_by_index = fn("pa_context_get_sink_info_by_index", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    static final MethodHandle pa_context_get_sink_info_list = fn("pa_context_get_sink_info_list", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle pa_context_set_subscribe_callback = fn("pa_context_set_subscribe_callback", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle pa_context_subscribe = fn("pa_context_subscribe", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));

    static final MethodHandle pa_operation_get_state = fn("pa_operation_get_state", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    static final MethodHandle pa_operation_unref = fn("pa_operation_unref", FunctionDescriptor.ofVoid(ADDRESS));

    static final MethodHandle pa_stream_new = fn("pa_stream_new", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle pa_stream_unref = fn("pa_stream_unref", FunctionDescriptor.ofVoid(ADDRESS));
    static final MethodHandle pa_stream_get_state = fn("pa_stream_get_state", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    static final MethodHandle pa_stream_set_state_callback = fn("pa_stream_set_state_callback", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle pa_stream_set_read_callback = fn("pa_stream_set_read_callback", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle pa_stream_connect_record = fn("pa_stream_connect_record", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_INT));
    static final MethodHandle pa_stream_disconnect = fn("pa_stream_disconnect", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    static final MethodHandle pa_stream_peek = fn("pa_stream_peek", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    static final MethodHandle pa_stream_drop = fn("pa_stream_drop", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    static final MethodHandle pa_stream_readable_size = fn("pa_stream_readable_size", FunctionDescriptor.of(SIZE_T, ADDRESS));

    private static MethodHandle fn(String name, FunctionDescriptor fd){
        return LINKER.downcallHandle(LIB.findOrThrow(name), fd);
    }

    /** Inverse of {@link #speaker}; unknown positions become AUX{index}. */
    static int position(NativeCaptureDevice.Speaker s, int index){
        return switch (s) {
            case MONO -> 0;
            case FL -> 1;
            case FR -> 2;
            case FC -> 3;
            case BC -> 4;
            case BL -> 5;
            case BR -> 6;
            case LFE -> 7;
            case FLC -> 8;
            case FRC -> 9;
            case SL -> 10;
            case SR -> 11;
            case TC -> 44;
            case TFL -> 45;
            case TFR -> 46;
            case TFC -> 47;
            case TBL -> 48;
            case TBR -> 49;
            case TBC -> 50;
            case OTHER -> 12 + Math.min(index, 31);
        };
    }

    /** Maps a pa_channel_position_t to a {@link NativeCaptureDevice.Speaker}. */
    static NativeCaptureDevice.Speaker speaker(int position){
        return switch (position) {
            case 0 -> NativeCaptureDevice.Speaker.MONO;
            case 1 -> NativeCaptureDevice.Speaker.FL;
            case 2 -> NativeCaptureDevice.Speaker.FR;
            case 3 -> NativeCaptureDevice.Speaker.FC;
            case 4 -> NativeCaptureDevice.Speaker.BC;   // rear center
            case 5 -> NativeCaptureDevice.Speaker.BL;   // rear left
            case 6 -> NativeCaptureDevice.Speaker.BR;   // rear right
            case 7 -> NativeCaptureDevice.Speaker.LFE;
            case 8 -> NativeCaptureDevice.Speaker.FLC;
            case 9 -> NativeCaptureDevice.Speaker.FRC;
            case 10 -> NativeCaptureDevice.Speaker.SL;
            case 11 -> NativeCaptureDevice.Speaker.SR;
            case 44 -> NativeCaptureDevice.Speaker.TC;
            case 45 -> NativeCaptureDevice.Speaker.TFL;
            case 46 -> NativeCaptureDevice.Speaker.TFR;
            case 47 -> NativeCaptureDevice.Speaker.TFC;
            case 48 -> NativeCaptureDevice.Speaker.TBL;
            case 49 -> NativeCaptureDevice.Speaker.TBR;
            case 50 -> NativeCaptureDevice.Speaker.TBC;
            default -> NativeCaptureDevice.Speaker.OTHER; // AUX0..31 (12..43) and invalid
        };
    }
}
