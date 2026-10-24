package com.EonCuber.config;

// this is so ugly and I love it
public final class Settings {
    // default values
    private static volatile barSmoothingMethod spectrogramBarSmoothingMethod = barSmoothingMethod.CubicSpline;
    private static volatile displayColorSpace randomColorGeneratorSpace = displayColorSpace.OkLAB;
    private static volatile displayColorSpace primaryDisplayColorSpace = displayColorSpace.sRGB;
    private static volatile displayColorSpace interpolationColorSpace = displayColorSpace.CIELAB;
    private static volatile scalarMethod frequencySampleScalarMethod = scalarMethod.Linear;
    private static volatile scalarMethod spectrogramScaleMethod = scalarMethod.Logarithmic;
    private static volatile float[][] primaryDisplayColors = {{1,0,0},{0,1,0},{0,0,1}};
    private static volatile double frequencyCyclesPerFrequencySample = 2;
    private static volatile double[] frequencyDisplayRange = {20,15000};
    private static volatile boolean isHardwareAccelerated = true;
    private static volatile boolean isHumanBiasActive = true;
    private static volatile int frequencySampleCount = 640;
    private static volatile double frequencyBiasLevel = 502;
    private static volatile float[] frequencyBiasData = {1,1,0.5f,0.2f};
    private static volatile int targetUpdateRate = 30;
    private static volatile int displayBarCount = 1280;

    private static String settingsPath;
    private static boolean initialized = false;
    public static void initSettings(){
        // TODO: load settings file from use storage
        initialized = true;
    }
    public static boolean hasInitialized(){
        return initialized;
    }
    private static synchronized void syncSettings(){
        // TODO: Save the current settings config to disk
    }
    // setting enums
    public enum barSmoothingMethod {CubicSpline,Linear,CatmullRoom,MonotoneCubic,Cosine,NearestNeighbor}
    public enum displayColorSpace {sRGB, OkLAB, HSV, CMYK, CIELAB}
    public enum scalarMethod {Linear,Logarithmic}
    // setting getters
    public static barSmoothingMethod getSpectrogramBarSmoothingMethod(){
        return spectrogramBarSmoothingMethod;
    }
    public static displayColorSpace getRandomColorGeneratorSpace(){
        return randomColorGeneratorSpace;
    }
    public static displayColorSpace getPrimaryDisplayColorSpace(){
        return primaryDisplayColorSpace;
    }
    public static displayColorSpace getInterpolationColorSpace(){
        return interpolationColorSpace;
    }
    public static scalarMethod getFrequencySampleScalarMethod(){
        return frequencySampleScalarMethod;
    }
    public static double getFrequencyCyclesPerFrequencySample(){
        return frequencyCyclesPerFrequencySample;
    }
    public static scalarMethod getSpectrogramScaleMethod(){
        return spectrogramScaleMethod;
    }
    public static float[][] getPrimaryDisplayColors(){
        return primaryDisplayColors;
    }
    public static double[] getFrequencyDisplayRange(){
        return frequencyDisplayRange;
    }
    public static boolean getIfHardwareAccelerated(){
        return isHardwareAccelerated;
    }
    public static int getFrequencySampleCount(){
        return frequencySampleCount;
    }
    public static boolean getHumanBiasActive(){
        return isHumanBiasActive;
    }
    public static double getFrequencyBiasLevel(){
        return frequencyBiasLevel;
    }
    public static float[] getFrequencyBiasData(){
        return frequencyBiasData;
    }
    public static int getTargetUpdateRate(){
        return targetUpdateRate;
    }
    public static int getDisplayBarCount(){
        return displayBarCount;
    }
    // setting setters
    public static void setSpectrogramBarSmoothingMethod(barSmoothingMethod newVal){
        spectrogramBarSmoothingMethod = newVal;
        syncSettings();
    }
    public static void setRandomColorGeneratorSpace(displayColorSpace newVal){
        randomColorGeneratorSpace = newVal;
        syncSettings();
    }
    public static void setPrimaryDisplayColorSpace(displayColorSpace newVal){
        primaryDisplayColorSpace = newVal;
        syncSettings();
    }
    public static void setInterpolationColorSpace(displayColorSpace newVal){
        interpolationColorSpace = newVal;
        syncSettings();
    }
    public static void setFrequencyCyclesPerFrequencySample(double newVal){
        frequencyCyclesPerFrequencySample = newVal;
        syncSettings();
    }
    public static void setFrequencySampleScalarMethod(scalarMethod newVal){
        frequencySampleScalarMethod = newVal;
        syncSettings();
    }
    public static void setSpectrogramScaleMethod(scalarMethod newVal){
        spectrogramScaleMethod = newVal;
        syncSettings();
    }
    public static void setPrimaryDisplayColors(float[][] newVal){
        primaryDisplayColors = newVal;
        syncSettings();
    }
    public static void setFrequencyDisplayRange(double[] newVal){
        frequencyDisplayRange = newVal;
        syncSettings();
    }
    public static void setIfHardwareAccelerated(boolean newVal){
        isHardwareAccelerated = newVal;
        syncSettings();
    }
    public static void setIsHumanBiasActive(boolean newVal){
        isHumanBiasActive = newVal;
        syncSettings();
    }
    public static void setFrequencySampleCount(int newVal){
        frequencySampleCount = newVal;
        syncSettings();
    }
    public static void setFrequencyBiasLevel(double newVal){
        frequencyBiasLevel = newVal;
        syncSettings();
    }
    public static void setFrequencyBiasData(float[] newVal){
        frequencyBiasData = newVal;
        syncSettings();
    }
    public static void setTargetUpdateRate(int newVal){
        targetUpdateRate = newVal;
        syncSettings();
    }
    public static void setDisplayBarCount(int newVal){
        displayBarCount = newVal;
        syncSettings();
    }
}
