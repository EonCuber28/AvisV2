package com.EonCuber.audio.platforms;

import com.EonCuber.audio.SystemAudioStreamDevice;

public interface AudioPlatform {
    SystemAudioStreamDevice[] availableDevices();
    void stop();
}
