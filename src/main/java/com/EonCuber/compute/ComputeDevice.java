package com.EonCuber.compute;

import com.EonCuber.audio.AudioBuffer;

public interface ComputeDevice {
    void setComputeInData(
            AudioBuffer inAudio,
            int sampleRate
    );
    void onFrame();
    Object getDisplay();
}
