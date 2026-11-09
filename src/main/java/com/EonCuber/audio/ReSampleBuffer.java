package com.EonCuber.audio;

public final class ReSampleBuffer {
    public static AudioBuffer reSampleBuffer(
            long inSampleRate,
            long outSampleRate,
            AudioBuffer inBuffer){
        // TODO
        return null;
    }
    private static float getSample(AudioBuffer buff, double idx){
        float dec = (float)(idx%1);
        if (dec == 0) return buff.getSample((int)idx);
        float t0 = buff.getSample((int)idx);
        float t1 = buff.getSample((int)idx+1);
        return t0*(1-dec) + t1*dec;
    }
}
