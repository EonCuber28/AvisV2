package com.EonCuber.compute.platforms.Vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.EXTDebugUtils.*;
import static org.lwjgl.vulkan.KHRPortabilityEnumeration.*;
import static org.lwjgl.vulkan.VK11.*;

// made by Claude Opus 5.5 high
public final class VulkanContext implements AutoCloseable {
    public final VkInstance instance;
    public final VkPhysicalDevice physicalDevice;
    public final VkDevice device;
    public final VkQueue queue;
    public final int queueFamily;

    private final long debugMessenger;
    private final VkDebugUtilsMessengerCallbackEXT debugCallback;

    public VulkanContext(boolean validation) {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer pp = stack.mallocPointer(1); // reusable output slot

            // ---------- 1. Instance ----------
            // macOS (MoltenVK) only shows its GPU if we opt in to "portability" drivers.
            boolean portability = hasInstanceExtension(stack, VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME);

            PointerBuffer extensions = stack.mallocPointer(2);
            if (validation)  extensions.put(stack.UTF8(VK_EXT_DEBUG_UTILS_EXTENSION_NAME));
            if (portability) extensions.put(stack.UTF8(VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME));
            extensions.flip();

            PointerBuffer layers = validation
                    ? stack.pointers(stack.UTF8("VK_LAYER_KHRONOS_validation"))
                    : null;

            VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack)
                    .sType$Default()
                    .pApplicationName(stack.UTF8("Compute"))
                    .apiVersion(VK_API_VERSION_1_1);      // the Vulkan version we target

            VkInstanceCreateInfo instanceInfo = VkInstanceCreateInfo.calloc(stack)
                    .sType$Default()
                    .flags(portability ? VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR : 0)
                    .pApplicationInfo(appInfo)
                    .ppEnabledLayerNames(layers)
                    .ppEnabledExtensionNames(extensions);

            check(vkCreateInstance(instanceInfo, null, pp));
            instance = new VkInstance(pp.get(0), instanceInfo);

            // ---------- 2. Validation messages -> System.err ----------
            if (validation) {
                debugCallback = VkDebugUtilsMessengerCallbackEXT.create(
                        (severity, types, pCallbackData, pUserData) -> {
                            var data = VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData);
                            System.err.println("[Vulkan] " + data.pMessageString());
                            return VK_FALSE;
                        });
                VkDebugUtilsMessengerCreateInfoEXT debugInfo = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack)
                        .sType$Default()
                        .messageSeverity(VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT
                                | VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
                        .messageType(VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT
                                | VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT
                                | VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)
                        .pfnUserCallback(debugCallback);
                LongBuffer pMessenger = stack.mallocLong(1);
                check(vkCreateDebugUtilsMessengerEXT(instance, debugInfo, null, pMessenger));
                debugMessenger = pMessenger.get(0);
            } else {
                debugCallback = null;
                debugMessenger = VK_NULL_HANDLE;
            }

            // ---------- 3. Pick a GPU (prefer a dedicated one) ----------
            IntBuffer count = stack.mallocInt(1);
            check(vkEnumeratePhysicalDevices(instance, count, null));     // how many?
            if (count.get(0) == 0) throw new IllegalStateException("No Vulkan GPU found");
            PointerBuffer gpus = stack.mallocPointer(count.get(0));
            check(vkEnumeratePhysicalDevices(instance, count, gpus));     // fill the list

            VkPhysicalDevice best = null;
            int bestFamily = -1;
            boolean bestIsDiscrete = false;
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
            for (int i = 0; i < gpus.capacity(); i++) {
                VkPhysicalDevice gpu = new VkPhysicalDevice(gpus.get(i), instance);
                int family = findComputeQueueFamily(stack, gpu);
                if (family < 0) continue;                                 // can't do compute
                vkGetPhysicalDeviceProperties(gpu, props);
                boolean discrete = props.deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU;
                if (best == null || (discrete && !bestIsDiscrete)) {
                    best = gpu; bestFamily = family; bestIsDiscrete = discrete;
                }
            }
            if (best == null) throw new IllegalStateException("No GPU with compute support");
            physicalDevice = best;
            queueFamily = bestFamily;
            vkGetPhysicalDeviceProperties(physicalDevice, props);
            System.out.println("Using GPU: " + props.deviceNameString());

            // ---------- 4. Logical device + queue ----------
            VkDeviceQueueCreateInfo.Buffer queueInfo = VkDeviceQueueCreateInfo.calloc(1, stack)
                    .sType$Default()
                    .queueFamilyIndex(queueFamily)
                    .pQueuePriorities(stack.floats(1.0f));   // one queue, normal priority

            // MoltenVK requires this extension to be switched on when it exists.
            PointerBuffer deviceExtensions = hasDeviceExtension(stack, physicalDevice, "VK_KHR_portability_subset")
                    ? stack.pointers(stack.UTF8("VK_KHR_portability_subset"))
                    : null;

            VkDeviceCreateInfo deviceInfo = VkDeviceCreateInfo.calloc(stack)
                    .sType$Default()
                    .pQueueCreateInfos(queueInfo)
                    .ppEnabledExtensionNames(deviceExtensions);
            check(vkCreateDevice(physicalDevice, deviceInfo, null, pp));
            device = new VkDevice(pp.get(0), physicalDevice, deviceInfo);

            vkGetDeviceQueue(device, queueFamily, 0, pp);
            queue = new VkQueue(pp.get(0), device);
        }
    }

    /** Finds a memory type that the resource allows (typeBits) and that has all the wanted properties. */
    public int findMemoryType(int typeBits, int wantedProperties) {
        try (MemoryStack stack = stackPush()) {
            VkPhysicalDeviceMemoryProperties mem = VkPhysicalDeviceMemoryProperties.malloc(stack);
            vkGetPhysicalDeviceMemoryProperties(physicalDevice, mem);
            for (int i = 0; i < mem.memoryTypeCount(); i++) {
                boolean allowed = (typeBits & (1 << i)) != 0;
                boolean hasProps = (mem.memoryTypes(i).propertyFlags() & wantedProperties) == wantedProperties;
                if (allowed && hasProps) return i;
            }
        }
        throw new IllegalStateException("No suitable memory type");
    }

    public static void check(int result) {
        if (result != VK_SUCCESS) throw new IllegalStateException("Vulkan call failed with code " + result);
    }

    private static int findComputeQueueFamily(MemoryStack stack, VkPhysicalDevice gpu) {
        IntBuffer count = stack.mallocInt(1);
        vkGetPhysicalDeviceQueueFamilyProperties(gpu, count, null);
        VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(count.get(0), stack);
        vkGetPhysicalDeviceQueueFamilyProperties(gpu, count, families);
        for (int i = 0; i < families.capacity(); i++) {
            if ((families.get(i).queueFlags() & VK_QUEUE_COMPUTE_BIT) != 0) return i;
        }
        return -1;
    }

    private static boolean hasInstanceExtension(MemoryStack stack, String name) {
        IntBuffer count = stack.mallocInt(1);
        check(vkEnumerateInstanceExtensionProperties((CharSequence) null, count, null));
        VkExtensionProperties.Buffer list = VkExtensionProperties.malloc(count.get(0), stack);
        check(vkEnumerateInstanceExtensionProperties((CharSequence) null, count, list));
        for (VkExtensionProperties e : list) if (e.extensionNameString().equals(name)) return true;
        return false;
    }

    private static boolean hasDeviceExtension(MemoryStack stack, VkPhysicalDevice gpu, String name) {
        IntBuffer count = stack.mallocInt(1);
        check(vkEnumerateDeviceExtensionProperties(gpu, (CharSequence) null, count, null));
        VkExtensionProperties.Buffer list = VkExtensionProperties.malloc(count.get(0), stack);
        check(vkEnumerateDeviceExtensionProperties(gpu, (CharSequence) null, count, list));
        for (VkExtensionProperties e : list) if (e.extensionNameString().equals(name)) return true;
        return false;
    }

    @Override
    public void close() {
        vkDeviceWaitIdle(device);   // let the GPU finish before tearing down
        vkDestroyDevice(device, null);
        if (debugMessenger != VK_NULL_HANDLE) vkDestroyDebugUtilsMessengerEXT(instance, debugMessenger, null);
        if (debugCallback != null) debugCallback.free();
        vkDestroyInstance(instance, null);
    }
}