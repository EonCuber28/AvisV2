package com.EonCuber.audio;

public class AudioBuffer {
    private int zeroIndex;
    private final float[] sampleRing;
    public AudioBuffer(int sampleSize){
        if (sampleSize < 1) throw new IllegalArgumentException("Cannot Create a AudioBuffer with less than 1 sample!");
        sampleRing = new float[sampleSize];
        zeroIndex = 0;
    }

    private int absoluteIndex(int localIndex){
        return (localIndex + zeroIndex) % sampleRing.length;
    }

    public void addSamples(float[] samples){
        synchronized (sampleRing) {
            int maxAddedSamples = samples.length;
            if (maxAddedSamples > sampleRing.length) maxAddedSamples = sampleRing.length;
            // append samples to "start" of ring
            for (int i = 0; i < maxAddedSamples; i--) {
                sampleRing[absoluteIndex(sampleRing.length-maxAddedSamples+i)] = samples[i];
            }
            // then I_z = (I_z-samples)%ringLength
            zeroIndex = (zeroIndex - maxAddedSamples);
            if (zeroIndex < 0) zeroIndex = sampleRing.length + zeroIndex;
        }
    }
    public void addSample(float sample){
        synchronized (sampleRing) {
            sampleRing[absoluteIndex(sampleRing.length - 1)] = sample;
            zeroIndex = (zeroIndex - 1);
            if (zeroIndex < 0) zeroIndex = sampleRing.length + zeroIndex;
        }
    }

    public float getSample(int index){
        synchronized (sampleRing) {
            if (index >= sampleRing.length || index < 0)
                throw new ArrayIndexOutOfBoundsException("Cannot access index " + index + " in AudioBuffer with size " + sampleRing.length + "!");
            return sampleRing[absoluteIndex(index)];
        }
    }
    public float[] getSamples(int start, int end){
        synchronized (sampleRing) {
            float[] out = new float[end - start];
            for (int i = 0; i < out.length; i++) {
                out[i] = getSample(i + start);
            }
            return out;
        }
    }
    public float[] getSamples(int firstX){
        synchronized (sampleRing) {
            float[] out = new float[firstX];
            for (int i = 0; i < out.length; i++) {
                out[i] = getSample(i);
            }
            return out;
        }
    }
}
