package com.EonCuber.compute.platforms.Vulkan;

import java.util.Vector;

// this is just like the float buffer,
// but instead we read and write in groups of 4 to and from the GPU
public class Vec4Buffer implements VulkanBuffer{
    private final int length; // number of vec4s in the buffer
    private final float[][] CPUBuffer;
    public Vec4Buffer(VulkanContext context, float[][] startingBuffer){
        length = startingBuffer.length;
        // normalize formatting
        CPUBuffer = new float[length][];
        for (int i = 0; i < length; i++){
            CPUBuffer[i] = normalizeVec4Format(startingBuffer[i]);
        }
        // initialize GPU buffer
        // write CPU side data to GPU
    }
    public Vec4Buffer(VulkanContext context, int length) {
        if (length < 0) throw new IllegalArgumentException("Length cannot be less than 0!");
        this(context, new float[length][]);
    }

    private float[] normalizeVec4Format(float[] vec4){
        // any null elements will become vectors with everything set to 0
        if (vec4 == null) {
            return new float[]{0, 0, 0, 0};
        }
        else if (vec4.length == 4){
            return vec4;
        }
        // any vectors with >4 values will have extra values truncated
        else if (vec4.length > 4){
            return new float[]{vec4[0],vec4[1],vec4[2],vec4[3]};
        }
        // any vectors with <4 values will have the remaining parts set to 0
        else if (vec4.length == 3){
            return new float[]{vec4[0],vec4[1],vec4[2],0};
        }
        else if (vec4.length == 2){
            return new float[]{vec4[0],vec4[1],0,0};
        }
        else if (vec4.length == 1){
            return new float[]{vec4[0],0,0,0};
        }
        else { // if (vec4.length == 0) {
            return new float[]{0,0,0,0};
        }
    }

    // these operations strictly operate with the local list copy
    public float[] get(int i){
        if (i >= length || i < 0) return new float[]{-1,-1,-1,-1};
        return CPUBuffer[i];
    }
    public void set(float[] value, int i){
        value = normalizeVec4Format(value);
        if (i >= length || i < 0) return;
        CPUBuffer[i] = value;
    }

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
        // release all manually allocated on CPU and GPU
        // TODO
    }
}
