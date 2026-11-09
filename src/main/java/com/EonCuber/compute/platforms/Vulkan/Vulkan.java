package com.EonCuber.compute.platforms.Vulkan;

import com.EonCuber.audio.AudioBuffer;
import com.EonCuber.compute.ComputeDevice;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.vkCreateShaderModule;

public class Vulkan implements ComputeDevice, AutoCloseable {
    // compute device stuffs
    private VulkanContext vkContext;
    // Vulkan ID storage
    private long inAudioBuffer;
    private long channelFrequencyData;
    private long frequencyBiasLUT;
    private long barHeightData;
    private long barRenderTexture;
    private long lastFrameTexture;
    private long outRenderTexture;
    // shader IDs
    private long QFTShader;

    private long OscilloscopeFrequencyCalcShader;
    private long OscilloscopeOffsetFinderShader;
    private long OscilloscopeRenderShader;

    private long BarHeightShader;
    private long BarDataShader;
    private long GayBarRender1; // I think my brother secretly
    private long GayBarRender2; // goes to these places
    private long BarRender;

    private long TransferColorShader;
    @Override
    public void init() {
        // TODO
        // setup compute device
        vkContext = new VulkanContext(true); // make this false when running the compiled version
        // load all the shaders
        QFTShader = loadShaderModule("/shaders/Vulkan/Pass1/QFT.spv");

        OscilloscopeFrequencyCalcShader = loadShaderModule("/shaders/Vulkan/Pass2/OscilloscopeFrequencyCalc.spv");
        OscilloscopeOffsetFinderShader = loadShaderModule("/shaders/Vulkan/Pass3/OscilloscopeOffsetFinder.spv");
        OscilloscopeRenderShader = loadShaderModule("/shaders/Vulkan/Pass4/OscilloscopeRender.spv");

        BarHeightShader = loadShaderModule("/shaders/Vulkan/Pass2/PerChannelBarHeightCalc.spv");
        BarDataShader = loadShaderModule("/shaders/Vulkan/Pass3/BarDataComp.spv");
        GayBarRender1 = loadShaderModule("/shaders/Vulkan/Pass4/Gay/GayBarRenderPass1.spv");
        GayBarRender2 = loadShaderModule("/shaders/Vulkan/Pass4/Gay/GayBarRenderPass2.spv");
        BarRender = loadShaderModule("/shaders/Vulkan/Pass4/Straight/BarRender.spv");

        TransferColorShader = loadShaderModule("/shaders/Vulkan/Pass5/transferColorSpace.spv");
        // initialize memory things
    }

    private long loadShaderModule(String shaderPath){
        byte[] bytes;
        try (InputStream in = VulkanContext.class.getResourceAsStream(shaderPath)){
            if (in == null) throw new FileNotFoundException(shaderPath);
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        ByteBuffer code = MemoryUtil.memAlloc(bytes.length); // CPU side copy of SPIR-V code
        try (MemoryStack stack = MemoryStack.stackPush()){
            code.put(bytes).flip();
            VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack)
                    .sType$Default()
                    .pCode(code);
            LongBuffer pModule = stack.mallocLong(1);
            VulkanContext.check(vkCreateShaderModule(vkContext.device, info, null, pModule));
            return pModule.get(0);
        } finally {
            MemoryUtil.memFree(code); // the driver already has the shader loaded, so no need to keep our version loaded
        }
    }

    @Override
    public void halt() {
        // TODO
    }
    @Override
    public void setComputeInData(AudioBuffer inAudio, int sampleRate) {
        // TODO
    }
    @Override
    public void onFrame() {
        // invoke QFT shader & wait for it to finish
        runQFTShader();
        // invoke oscilloscope shader pipeline
        runOscilloscopePipeline();
        // invoke bar visuals pipeline
        runBarVisualsPipeline();
        // convert renders to rgb color space
        // blit each render to the window
        blitVisualsToWindow();
    }

    private void runQFTShader(){
        // TODO
        // generate push_constant data
        // invoke the shader
        // wait for shader to complete processing
    }

    private void runOscilloscopePipeline(){
        // TODO
        // call the frequency finder
        // gate next call until shader is done
        // call offset finder
        // gate next call until that single fucking slut of a thread is done taking its sweet goddamn time calculating a 1-6 value average and a single division.
        // call oscilloscope render
    }
    private void runBarVisualsPipeline(){
        // TODO
        // call the
    }

    private void blitVisualsToWindow(){
        // TODO
    }

    @Override
    public void close() throws Exception {
        // TODO
    }
}