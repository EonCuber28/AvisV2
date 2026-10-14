package com.EonCuber.config;

public final class AppDirs {
    private static String OS = System.getProperty("os.name").toLowerCase();
    public final static boolean IS_WINDOWS = (OS.indexOf("win") >= 0);
    public final static boolean IS_MAC = (OS.indexOf("mac") >= 0);
    public final static boolean IS_UNIX = (!IS_MAC && !IS_WINDOWS);
    private static String appDir;
    public static void init(){
        if (IS_WINDOWS) appDir = "%AppData%\\Avis";
        else if (IS_MAC) appDir = "~/Library/Application Support/Avis";
        else appDir = "~/.config/avis"; // default to unix path
    }
}