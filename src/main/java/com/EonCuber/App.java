package com.EonCuber;

public class App {
    // this is to take care of running the actual window and frame loop code
    // when the window is minimized, it should only be running the audio buffer loop and no graphics or FT processing
    // when re-opened, it should resume the FT processing and rendering code from where it last was.
}

class SettingsMenu{
    public void activate(){}
    public void deactivate(){}
    public void onFrame(){}
}