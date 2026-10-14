package com.EonCuber.audio;

import com.EonCuber.audio.platforms.AudioPlatform;
import com.EonCuber.audio.platforms.MacOS_CoreAudio;
import com.EonCuber.audio.platforms.Unix_PulseAudio;
import com.EonCuber.audio.platforms.Win_WASAPI;
import com.EonCuber.config.AppDirs;

public class AudioManager {
    private final AudioPlatform thisPlatform;

    private SystemAudioStreamDevice activeDevice;
    public AudioManager(){
        // using OS info, load the related AudioPlatform
        if (AppDirs.IS_WINDOWS){
            thisPlatform = new Win_WASAPI();
        } else if (AppDirs.IS_MAC){
            thisPlatform = new MacOS_CoreAudio();
        } else {
            thisPlatform = new Unix_PulseAudio();
        }
        // setup audio buffer
        loadAudioDevice(0);
    }

    public void loadAudioDevice(int deviceIndex){
        activeDevice = thisPlatform.availableDevices()[deviceIndex];
        activeDevice.activate();
    }

    public SystemAudioStreamDevice[] getAvailableDevices(){
        return thisPlatform.availableDevices();
    }
}
