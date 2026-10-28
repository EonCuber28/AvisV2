package com.EonCuber.compute;

import com.EonCuber.audio.AudioBuffer;

public interface ComputeDevice {
    void init();
    void halt();
    void setComputeInData(
            AudioBuffer inAudio,
            int sampleRate);
    void onFrame();
}
