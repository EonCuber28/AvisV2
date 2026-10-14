package com.EonCuber.audio.platforms;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.*;

/**
 * Minimal COM / WASAPI / kernel32 bindings (checked against mingw-w64 audioclient.h, mmdeviceapi.h,
 * functiondiscoverykeys_devpkey.h). Loaded lazily: only touched from {@link Win_WASAPI}.
 * <p>
 * COM methods are called through the object's vtable: {@link #fn(MemorySegment, int)} reads the function pointer,
 * and the {@code V_*} handles (created without a target address) take it as their first argument.
 */
final class ComBindings {
    private ComBindings(){}

    static final Linker LINKER = Linker.nativeLinker();
    static final SymbolLookup OLE32 = SymbolLookup.libraryLookup("ole32.dll", Arena.global());
    static final SymbolLookup KERNEL32 = SymbolLookup.libraryLookup("kernel32.dll", Arena.global());
    static final SymbolLookup WINMM = SymbolLookup.libraryLookup("winmm.dll", Arena.global());

    // ---------------------------------------------------------------- constants
    static final int S_OK = 0, S_FALSE = 1;
    static final int RPC_E_CHANGED_MODE = 0x80010106;
    static final int E_NOINTERFACE = 0x80004002;
    static final int COINIT_MULTITHREADED = 0x0;
    static final int CLSCTX_ALL = 0x17;
    static final int eRender = 0, eConsole = 0;
    static final int DEVICE_STATE_ACTIVE = 0x1;
    static final int STGM_READ = 0;
    static final short VT_LPWSTR = 31;
    static final int AUDCLNT_SHAREMODE_SHARED = 0;
    static final int AUDCLNT_STREAMFLAGS_LOOPBACK = 0x00020000;
    static final int AUDCLNT_STREAMFLAGS_EVENTCALLBACK = 0x00040000;
    static final int AUDCLNT_BUFFERFLAGS_SILENT = 0x2;
    static final int AUDCLNT_S_BUFFER_EMPTY = 0x08890001;
    static final int AUDCLNT_E_DEVICE_INVALIDATED = 0x88890004;
    static final int AUDCLNT_E_ENGINE_PERIODICITY_LOCKED = 0x88890028;
    static final int AUDCLNT_E_SERVICE_NOT_RUNNING = 0x88890010;
    static final int AUDCLNT_E_RESOURCES_INVALIDATED = 0x88890026;
    static final int WAIT_OBJECT_0 = 0, WAIT_TIMEOUT = 0x102, WAIT_FAILED = 0xFFFFFFFF;
    static final int WAVE_FORMAT_PCM = 1, WAVE_FORMAT_IEEE_FLOAT = 3, WAVE_FORMAT_EXTENSIBLE = 0xFFFE;

    // vtable indices (IUnknown = 0..2)
    static final int RELEASE = 2;
    static final int ENUM_EnumAudioEndpoints = 3, ENUM_GetDefaultAudioEndpoint = 4, ENUM_GetDevice = 5,
            ENUM_RegisterEndpointNotificationCallback = 6, ENUM_UnregisterEndpointNotificationCallback = 7;
    static final int COLL_GetCount = 3, COLL_Item = 4;
    static final int DEV_Activate = 3, DEV_OpenPropertyStore = 4, DEV_GetId = 5;
    static final int PROPS_GetValue = 5;
    static final int AC_Initialize = 3, AC_GetBufferSize = 4, AC_GetCurrentPadding = 6, AC_GetMixFormat = 8,
            AC_GetDevicePeriod = 9, AC_Start = 10, AC_Stop = 11, AC_SetEventHandle = 13, AC_GetService = 14;
    static final int AC3_GetSharedModeEnginePeriod = 18, AC3_GetCurrentSharedModeEnginePeriod = 19,
            AC3_InitializeSharedAudioStream = 20;
    static final int CAP_GetBuffer = 3, CAP_ReleaseBuffer = 4, CAP_GetNextPacketSize = 5;
    static final int REN_GetBuffer = 3, REN_ReleaseBuffer = 4;

    // GUIDs
    static final MemorySegment CLSID_MMDeviceEnumerator = guid("bcde0395-e52f-467c-8e3d-c4579291692e");
    static final MemorySegment IID_IUnknown = guid("00000000-0000-0000-c000-000000000046");
    static final MemorySegment IID_IMMDeviceEnumerator = guid("a95664d2-9614-4f35-a746-de8db63617e6");
    static final MemorySegment IID_IMMNotificationClient = guid("7991eec9-7e89-4d85-8390-6c703cec60c0");
    static final MemorySegment IID_IAudioClient = guid("1cb9ad4c-dbfa-4c32-b178-c2f568a703b2");
    static final MemorySegment IID_IAudioClient3 = guid("7ed4ee07-8e67-4cd4-8c1a-2b7a5987ad42");
    static final MemorySegment IID_IAudioCaptureClient = guid("c8adbd64-e71e-48a0-a4de-185c395cd317");
    static final MemorySegment IID_IAudioRenderClient = guid("f294acfc-3146-4483-a7bf-addca7c260e2");
    /** PROPERTYKEY {GUID fmtid; DWORD pid} */
    static final StructLayout PROPERTYKEY = MemoryLayout.structLayout(
            JAVA_INT, JAVA_SHORT, JAVA_SHORT, MemoryLayout.sequenceLayout(8, JAVA_BYTE), JAVA_INT);
    static final MemorySegment PKEY_Device_FriendlyName = propertyKey("a45c254e-df1c-4efd-8020-67d146a850e0", 14);
    /** PROPVARIANT is 24 bytes on 64-bit: vt at 0, value union at 8. */
    static final long PROPVARIANT_SIZE = 24;

    // ---------------------------------------------------------------- exported functions
    static final MethodHandle CoInitializeEx = fn(OLE32, "CoInitializeEx", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    static final MethodHandle CoUninitialize = fn(OLE32, "CoUninitialize", FunctionDescriptor.ofVoid());
    static final MethodHandle CoCreateInstance = fn(OLE32, "CoCreateInstance",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    static final MethodHandle CoTaskMemFree = fn(OLE32, "CoTaskMemFree", FunctionDescriptor.ofVoid(ADDRESS));
    static final MethodHandle PropVariantClear = fn(OLE32, "PropVariantClear", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    static final MethodHandle CreateEventW = fn(KERNEL32, "CreateEventW",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
    static final MethodHandle SetEvent = fn(KERNEL32, "SetEvent", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    static final MethodHandle CloseHandle = fn(KERNEL32, "CloseHandle", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    static final MethodHandle WaitForMultipleObjects = fn(KERNEL32, "WaitForMultipleObjects",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
    static final MethodHandle timeBeginPeriod = fn(WINMM, "timeBeginPeriod", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    static final MethodHandle timeEndPeriod = fn(WINMM, "timeEndPeriod", FunctionDescriptor.of(JAVA_INT, JAVA_INT));

    // ---------------------------------------------------------------- vtable call shapes (first arg = fn pointer)
    /** HRESULT f(this) */
    static final MethodHandle V_P = vt(FunctionDescriptor.of(JAVA_INT, ADDRESS));
    /** HRESULT f(this, void*) */
    static final MethodHandle V_PP = vt(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    /** HRESULT f(this, void*, void*) */
    static final MethodHandle V_PPP = vt(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    /** HRESULT f(this, int, void*) */
    static final MethodHandle V_PIP = vt(FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
    /** HRESULT f(this, int, int, void*) */
    static final MethodHandle V_PIIP = vt(FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
    /** HRESULT f(this, int) */
    static final MethodHandle V_PI = vt(FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    /** HRESULT f(this, int, int) */
    static final MethodHandle V_PII = vt(FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT));
    /** IMMDevice::Activate(this, REFIID, DWORD, PROPVARIANT*, void**) */
    static final MethodHandle V_ACTIVATE = vt(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    /** IAudioClient::Initialize(this, mode, flags, REFERENCE_TIME, REFERENCE_TIME, WAVEFORMATEX*, GUID*) */
    static final MethodHandle V_INITIALIZE = vt(FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_LONG, JAVA_LONG, ADDRESS, ADDRESS));
    /** IAudioClient3::GetSharedModeEnginePeriod(this, WAVEFORMATEX*, UINT32* x4) */
    static final MethodHandle V_ENGINE_PERIOD = vt(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    /** IAudioClient3::InitializeSharedAudioStream(this, flags, periodFrames, WAVEFORMATEX*, GUID*) */
    static final MethodHandle V_INIT_SHARED = vt(FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
    /** IAudioCaptureClient::GetBuffer(this, BYTE**, UINT32*, DWORD*, UINT64*, UINT64*) */
    static final MethodHandle V_CAPTURE_GETBUFFER = vt(FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

    // ---------------------------------------------------------------- IMMNotificationClient upcall shapes
    static final FunctionDescriptor NC_QUERY_INTERFACE = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
    static final FunctionDescriptor NC_ADDREF_RELEASE = FunctionDescriptor.of(JAVA_INT, ADDRESS);
    static final FunctionDescriptor NC_STATE_CHANGED = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT);
    static final FunctionDescriptor NC_ADDED_REMOVED = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS);
    static final FunctionDescriptor NC_DEFAULT_CHANGED = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS);
    static final FunctionDescriptor NC_PROPERTY_CHANGED = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, PROPERTYKEY);

    // ---------------------------------------------------------------- helpers

    private static MethodHandle fn(SymbolLookup lib, String name, FunctionDescriptor fd){
        return LINKER.downcallHandle(lib.findOrThrow(name), fd);
    }

    private static MethodHandle vt(FunctionDescriptor fd){
        return LINKER.downcallHandle(fd);
    }

    /** Reads entry {@code index} of a COM object's vtable. */
    static MemorySegment fn(MemorySegment comObject, int index){
        MemorySegment vtbl = comObject.reinterpret(ADDRESS.byteSize()).get(ADDRESS, 0);
        return vtbl.reinterpret(ADDRESS.byteSize() * (index + 1)).getAtIndex(ADDRESS, index);
    }

    /** Throws if the HRESULT is a failure code. */
    static int check(int hr, String what){
        if (hr < 0) throw new ComException(hr, what);
        return hr;
    }

    static void release(MemorySegment comObject){
        if (comObject == null || comObject.address() == 0) return;
        try {
            int ignored = (int) V_P.invokeExact(fn(comObject, RELEASE), comObject);
        } catch (Throwable t) {
            NativeCaptureDevice.log("IUnknown::Release failed", t);
        }
    }

    static void coTaskMemFree(MemorySegment p){
        if (p == null || p.address() == 0) return;
        try {
            CoTaskMemFree.invokeExact(p);
        } catch (Throwable t) {
            NativeCaptureDevice.log("CoTaskMemFree failed", t);
        }
    }

    static String wideString(MemorySegment p){
        return p.address() == 0 ? null : p.reinterpret(Long.MAX_VALUE).getString(0, StandardCharsets.UTF_16LE);
    }

    static MemorySegment guid(String s){
        return writeGuid(Arena.global().allocate(16, 4), 0, s);
    }

    static MemorySegment propertyKey(String fmtid, int pid){
        MemorySegment key = Arena.global().allocate(PROPERTYKEY);
        writeGuid(key, 0, fmtid);
        key.set(JAVA_INT, 16, pid);
        return key;
    }

    private static MemorySegment writeGuid(MemorySegment seg, long offset, String s){
        String hex = s.replace("-", "");
        seg.set(JAVA_INT_UNALIGNED.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN), offset, (int) Long.parseLong(hex.substring(0, 8), 16));
        seg.set(JAVA_SHORT_UNALIGNED.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN), offset + 4, (short) Integer.parseInt(hex.substring(8, 12), 16));
        seg.set(JAVA_SHORT_UNALIGNED.withOrder(java.nio.ByteOrder.LITTLE_ENDIAN), offset + 6, (short) Integer.parseInt(hex.substring(12, 16), 16));
        for (int i = 0; i < 8; i++)
            seg.set(JAVA_BYTE, offset + 8 + i, (byte) Integer.parseInt(hex.substring(16 + 2 * i, 18 + 2 * i), 16));
        return seg;
    }

    static boolean sameGuid(MemorySegment a, MemorySegment b){
        return a.address() != 0 && a.reinterpret(16).mismatch(b.reinterpret(16)) == -1;
    }

    /** A failed HRESULT. */
    static final class ComException extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        final int hr;
        ComException(int hr, String what){
            super(what + " failed (HRESULT 0x" + Integer.toHexString(hr).toUpperCase() + ")");
            this.hr = hr;
        }
    }
}
