package com.EonCuber.compute.platforms;

import com.EonCuber.audio.AudioBuffer;
import com.EonCuber.compute.ComputeDevice;

// do this when we make a web version where we can *hopefully* capture system audio,
// or we just make it a fancy mp3 player idk.
public class WebGPU implements ComputeDevice {
    @Override
    public void init() {
        // TODO
    }
    @Override
    public void halt() {
        // TODO
    }
    @Override
    public void setComputeInData(AudioBuffer inAudio, int sampleRate) {
        // TODO
    }
    @Override
    public void onFrame() {
        // TODO
    }
}
