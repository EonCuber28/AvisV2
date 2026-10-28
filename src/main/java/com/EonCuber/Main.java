package com.EonCuber;

import com.EonCuber.audio.AudioBuffer;
import com.EonCuber.audio.AudioManager;
import com.EonCuber.audio.SystemAudioStreamDevice;
import com.EonCuber.compute.ComputeDeviceManager;

import java.io.InputStream;

// you may only say "horse" in this repo when it becomes stable

public class Main {
    private static AudioManager audioManager;
    private static ComputeDeviceManager computeDevice;
    private static App app;
    public static void main(String[] args){
        // start audio systems
        audioManager = new AudioManager();
        audioManager.loadAudioDevice(0);
        // start compute systems
        computeDevice = new ComputeDeviceManager();
        computeDevice.init();
        // start app code
        app = new App(audioManager,computeDevice);
        // start frame loop
        while (app.isRunning()) {
            app.onFrame();
        }
        // close and clean up app
        closeNcleanup();
    }
    private static void closeNcleanup(){
        // close app
        app.halt();
        // shutdown audio systems
        audioManager.halt();
        // shutdown compute systems
        computeDevice.halt();
        // ensure everything is saved
        System.exit(0);
    }
}
