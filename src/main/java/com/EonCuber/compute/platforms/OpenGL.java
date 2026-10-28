package com.EonCuber.compute.platforms;

import com.EonCuber.audio.AudioBuffer;
import com.EonCuber.compute.ComputeDevice;

public class OpenGL implements ComputeDevice {
    // OpenGL ID storage
    private long inAudioBuffer;
    private long channelFrequencyData;
    private long frequencyBiasLUT;
    private long barHeightData;
    private long barRenderTexture;
    private long lastFrameTexture;
    private long outRenderTexture;
    // compute device stuffs
    @Override
    public void init() {
        // TODO
        // load all the shaders
        // initialise all GPU memory things
    }

    private int loadShader(String shaderPath){
        // TODO
        return 0;
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