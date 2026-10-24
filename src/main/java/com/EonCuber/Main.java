package com.EonCuber;

import com.EonCuber.audio.AudioBuffer;
import com.EonCuber.audio.AudioManager;
import com.EonCuber.audio.SystemAudioStreamDevice;

import java.io.InputStream;

// you may only say "horse" in this repo when it becomes stable

public class Main {
    public static void main(String[] args) throws Exception{
        buildTest();
        // start audio systems
        // start compute systems
        // start app code
        // start frame loop
        // shutdown audio systems
        // shutdown compute systems
        // ensure everything is saved
    }
    public static void buildTest() throws Exception{
        // build smoke test: are the shaders embedded, and does native audio capture work?
        for (String shader : new String[]{"QFT.comp", "BarDataCalculate.comp", "BarRender.comp"}){
            try (InputStream in = Main.class.getResourceAsStream("/shaders/" + shader)){
                System.out.println(shader + ": " + (in == null ? "MISSING" : in.readAllBytes().length + " bytes"));
            }
        }

        AudioManager audio = new AudioManager(); // activates device 0
        SystemAudioStreamDevice device = audio.getAvailableDevices()[0];
        System.out.println("Capturing " + device.deviceName() + " (" + device.channelConfig() + ", " + device.sampleRate() + " Hz)");
        for (int i = 0; i < 10; i++){
            Thread.sleep(500);
            float peak = 0;
            for (AudioBuffer b : device.activeBuffers())
                for (float s : b.getSamples(256)) peak = Math.max(peak, Math.abs(s));
            System.out.printf("peak %.3f%n", peak);
        }
        device.deactivate();
        System.exit(0);
    }

}
