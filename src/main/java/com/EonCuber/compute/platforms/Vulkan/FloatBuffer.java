package com.EonCuber.compute.platforms.Vulkan;

import java.util.Vector;

// luckily for both of the buffer classes,
// because our formatting is only using std430
// we don't have to majorly channge the processed between the two
public class FloatBuffer implements VulkanBuffer{
    private final long length; // in number of floats in the buffer
    private final float[] CPUBuffer;
    public FloatBuffer(float[] startingBuffer){
        length = startingBuffer.length;
        CPUBuffer = startingBuffer.clone();
    }
    public FloatBuffer(int length){
        this(new float[length]);
    }

    // these operations strictly operate with the local list copy
    public float get(int i){
        if (i >= length || i < 0) return -1;
        return CPUBuffer[i];
    }
    public void set(float value, int i){
        if (i >= length || i < 0) return;
        CPUBuffer[i] = value;
    }

    @Override
    public long javaPointer() {
        // TODO
        return 0;
    }
    @Override
    public long vkPointer() {
        // TODO
        return 0;
    }

    @Override
    public long byteSize() {
        return (long)length*4*Float.BYTES;
    }
    @Override
    public long size() {
        return length;
    }

    @Override
    public void push(){
        // TODO
        // write all data from this side into the GPU
    }
    @Override
    public void pull(){
        // TODO
        // write all data from the GPU side to the CPU side
    }

    @Override
    public void release() {
        // release all manually allocated
        // TODO
    }
}
