package com.EonCuber;

import com.EonCuber.audio.AudioManager;
import com.EonCuber.compute.ComputeDeviceManager;

public class App {
    private boolean running;
    public App(AudioManager audioMan, ComputeDeviceManager computeDevice){
        running = true;
        // TODO
    }
    // this is to take care of running the actual window and frame loop code
    // when the window is minimized, it should only be running the audio buffer loop and no graphics or FT processing
    // when re-opened, it should resume the FT processing and rendering code from where it last was.
    public void onFrame(){
        // TODO
    }
    public boolean isRunning(){
        return running;
    }
    public void halt(){
        // TODO
    }
}

class SettingsMenu{
    private boolean active = false;
    public void activate(){
        // TODO
    }
    public void deactivate(){
        // TODO
    }
    public void onFrame(){
        if (!active) return;
        // TODO
    }
}