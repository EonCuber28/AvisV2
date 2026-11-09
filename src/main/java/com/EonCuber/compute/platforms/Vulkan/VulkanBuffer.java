package com.EonCuber.compute.platforms.Vulkan;

public interface VulkanBuffer {
    long javaPointer();
    long vkPointer();
    long byteSize();
    void release();
    void push();
    void pull();
    long size();
}
