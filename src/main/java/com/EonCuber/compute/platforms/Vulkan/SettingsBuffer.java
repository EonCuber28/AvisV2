package com.EonCuber.compute.platforms.Vulkan;

public class SettingsBuffer implements VulkanBuffer{
    // its layout like so:
    // | byte I | GLSL type | setting name                |
    // |- - - - | - - - - - | - - - - - - - - - - - - - - |
    // | 0      | uint      | Sample Rate in Hz           |
    // | 4      | uint      | Per-channel Sample count    |
    // | 8      | uint      | Channel Count               |
    // | 12     | uint      | Frequency sample count      |
    // | 16     | uint      | bar interpolation method    |
    // | 20     | uint      | display bar count           |
    // | 24     | uint      | bar render color space      |
    // | 28     | uint      | osc render color space      |
    // | 32     | float     | frequency cycles per sample |
    // | 36     | float     | osc min freq delta          |
    // | 40     | float     | color smear decay factor    |
    // | 44     | bool      | use imaginary vec for QFT   |
    // | 48     | vec2      | color smear vec             |
    // | 56     | null      | *8 bytes of padding*        |
    // | 64     | vec4      | BG color                    |
    // | 80     | vec4      | osc wave color              |
    // | 96     | vec4      | osc center color            |
    // total byte length: 104
    @Override
    public long javaPointer() {
        return -1;
    }
    @Override
    public long vkPointer() {
        return -1;
    }

    @Override
    public long byteSize() {
        return 104;
    }

    @Override
    public void release() {}
    @Override
    public void push() {}
    @Override
    public void pull() {}

    @Override
    public long size() {
        return 832;
    }
}
