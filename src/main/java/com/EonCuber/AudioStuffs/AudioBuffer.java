package com.EonCuber.AudioStuffs;

import com.EonCuber.AudioStuffs.DeviceStuffs.AudioDevice;

public class AudioBuffer {
    // this allows the reading and writing of a large amount of audio samples from any thread.
    private final float[] samples;
    private int IDX0;

    public AudioBuffer(int size){
        samples = new float[size];
    }

    private int index(int i){
        // check for OOB
        if (i >= samples.length) throw new ArrayIndexOutOfBoundsException(i);
        // shift by ""zero index""
        i += IDX0;
        // loop around
        i %= samples.length;
        return i;
    }

    public void addSamples(float[] newSamples){
        // add samples into the buffer with respect to the ""zero index""
        for (int i = 0; i < newSamples.length; i++){
            samples[index(i)] = newSamples[0];
        }
        IDX0 += newSamples.length;
        IDX0 %= samples.length;
    }

    public float[] getSampleRange(int start, int end){
        // retrieve the samples in th given range with respect to the ""zero index""
        // create output array
        float[] sampleRange = new float[end-start];
        // retrieve samples from range
        for (int i = start; i < end; i++){
            sampleRange[i-start] = samples[index(i)];
        }
        return sampleRange;
    }
}
