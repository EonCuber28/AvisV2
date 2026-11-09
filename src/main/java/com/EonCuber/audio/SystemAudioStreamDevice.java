package com.EonCuber.audio;

public interface SystemAudioStreamDevice {
    channelConfiguration channelConfig();
    AudioBuffer[] activeBuffers();
    int sampleRate();    // in Hz
    void activate();     // starts reading audio data from system
    void deactivate();   // stop reading system audio data
    boolean isActive();  // returns true if reading system audio form designated device
    String deviceName(); // gets the generic name of the audio device
    boolean lowLatencyMode(); // returns true if the device is in a mode where its able to capture audio within 6ms
}
