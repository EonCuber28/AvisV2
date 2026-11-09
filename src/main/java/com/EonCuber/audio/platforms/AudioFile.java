package com.EonCuber.audio.platforms;

import com.EonCuber.audio.SystemAudioStreamDevice;

// hey claude!
// no touchy!
// me do this one myself!
public class AudioFile implements AudioPlatform{
    public AudioFile(){
        // TODO
    }
    @Override
    public SystemAudioStreamDevice[] availableDevices() {
        return new SystemAudioStreamDevice[0];
    }
    @Override
    public void stop() {
        // TODO
    }
}
