package dev.upscaler.rt;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanDevice;

import dev.upscaler.UpscalerMod;
import dev.upscaler.rt.pipeline.RtDlssFg;

import it.unimi.dsi.fastutil.longs.LongList;

import net.minecraft.client.Minecraft;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

/**
 * DLSS Frame Generation present engine (slice 2). Drives extra swapchain acquire→copy→present cycles so
 * more than one image can be shown per rendered frame (the generated frame(s), then the real frame).
 *
 * <p><b>Iteration 1 — present machinery only.</b> The "generated" frame is currently a copy of the final
 * rendered frame (no DLSSG evaluate wired yet); this isolates the hard Vulkan plumbing (own acquire
 * semaphore pool, own {@code vkAcquireNextImageKHR}/{@code vkQueuePresentKHR}, barriers, Y-flipped blit
 * modelled on the GPU-verified {@code RtComposite.presentHdr}) from the eval. Slice 2b swaps the duplicate
 * for the interpolated frame from {@link RtDlssFg}. Only engaged on the non-HDR present path; HDR+FG is
 * deferred (HDR present cancels {@code blitFromTexture} at HEAD, so the FG TAIL hook does not run there).
 */
public final class RtFramePresenter {
    public static final RtFramePresenter INSTANCE = new RtFramePresenter();

    private static final long ACQUIRE_TIMEOUT_NS = 5_000_000_000L;

    private long[] acquireSemaphores = new long[0];
    private int acquireCursor;
    private boolean failed;

    private RtFramePresenter() {
    }

    /** Whether FG extra-present should run this frame (enabled, available, in a world, non-HDR path). */
    public boolean isActive() {
        return !failed && RtDlssFg.enabled() && RtDlssFg.INSTANCE.isAvailable()
                && Minecraft.getInstance().level != null;
    }

    /**
     * Present {@code generatedCount} extra frames (currently duplicates of {@code srcImage}) into freshly
     * acquired swapchain images, before the caller (Minecraft's {@code present()}) presents the real frame
     * already blitted into its acquired image. {@code srcImage} is the final rendered frame (GENERAL layout,
     * the same image MC just blitted), in render/display pixels {@code srcW x srcH}. Failures latch FG off
     * for the session and fall back to the normal single present.
     */
    public void presentExtraFrames(VulkanCommandEncoder enc, VulkanDevice device, long swapchain, VkQueue presentQueue,
            LongList swapchainImages, long[] presentSemaphores, int swapW, int swapH,
            long srcImage, int srcW, int srcH, int generatedCount) {
        if (failed || swapchain == 0L || srcImage == 0L || generatedCount <= 0) {
            return;
        }
        try {
            ensureSemaphores(device, swapchainImages.size() + 1);
            for (int i = 0; i < generatedCount; i++) {
                if (!presentOne(enc, device, swapchain, presentQueue, swapchainImages, presentSemaphores,
                        swapW, swapH, srcImage, srcW, srcH)) {
                    return; // swapchain out-of-date/suboptimal: skip the rest, let MC recover
                }
            }
        } catch (Throwable t) {
            failed = true;
            UpscalerMod.LOGGER.error("DLSS-FG present failed; frame generation disabled", t);
        }
    }

    private boolean presentOne(VulkanCommandEncoder enc, VulkanDevice device, long swapchain, VkQueue presentQueue,
            LongList swapchainImages, long[] presentSemaphores, int swapW, int swapH,
            long srcImage, int srcW, int srcH) {
        long acquireSem = acquireSemaphores[acquireCursor];
        acquireCursor = (acquireCursor + 1) % acquireSemaphores.length;

        int imageIndex;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pIndex = stack.callocInt(1);
            int r = KHRSwapchain.vkAcquireNextImageKHR(device.vkDevice(), swapchain, ACQUIRE_TIMEOUT_NS, acquireSem, 0L, pIndex);
            // Out-of-date / timeout / error: bail and let MC's normal acquire/present recover next frame.
            if (r != VK10.VK_SUCCESS && r != 1000001003 /* SUBOPTIMAL */) {
                return false;
            }
            imageIndex = pIndex.get(0);
        }

        long dstImage = swapchainImages.getLong(imageIndex);
        long presentSem = presentSemaphores[imageIndex];
        int copyW = Math.min(swapW, srcW);
        int copyH = Math.min(swapH, srcH);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer cmd = enc.allocateAndBeginTransientCommandBuffer();

            // Swapchain UNDEFINED -> TRANSFER_DST (magic stage/access values mirror MC's blitFromTexture).
            VkImageMemoryBarrier2.Buffer toDst = VkImageMemoryBarrier2.calloc(1, stack).sType$Default();
            toDst.get(0).srcStageMask(0L).srcAccessMask(0L).dstStageMask(4096L).dstAccessMask(4096L)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(dstImage);
            toDst.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VkDependencyInfo dep1 = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(toDst);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep1);

            // Blit final frame (GENERAL) -> swapchain (TRANSFER_DST), Y-flipped like vanilla.
            VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
            region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).srcOffsets(1).set(copyW, copyH, 1); // srcOffsets[0] = (0,0,0) from calloc
            region.get(0).dstOffsets(0).set(0, copyH, 0);
            region.get(0).dstOffsets(1).set(copyW, 0, 1);
            VK10.vkCmdBlitImage(cmd, srcImage, VK10.VK_IMAGE_LAYOUT_GENERAL, dstImage,
                    VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region, VK10.VK_FILTER_NEAREST);

            // Swapchain TRANSFER_DST -> PRESENT_SRC_KHR (1000001002).
            VkImageMemoryBarrier2.Buffer toPresent = VkImageMemoryBarrier2.calloc(1, stack).sType$Default();
            toPresent.get(0).srcStageMask(4096L).srcAccessMask(4096L).dstStageMask(65536L).dstAccessMask(0L)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).newLayout(1000001002)
                    .srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(dstImage);
            toPresent.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VkMemoryBarrier2.Buffer mem2 = VkMemoryBarrier2.calloc(1, stack).sType$Default();
            mem2.get(0).srcStageMask(4096L).srcAccessMask(2048L).dstStageMask(65536L).dstAccessMask(98304L);
            VkDependencyInfo dep2 = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(toPresent).pMemoryBarriers(mem2);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep2);

            if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
                throw new IllegalStateException("vkEndCommandBuffer(fg present) failed");
            }
            enc.waitSemaphore(acquireSem, 0L, 65536L);
            enc.execute(cmd);
            enc.signalSemaphore(presentSem, 0L, 4096L);
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPresentInfoKHR present = VkPresentInfoKHR.calloc(stack).sType$Default();
            present.pWaitSemaphores(stack.longs(presentSem));
            present.swapchainCount(1);
            present.pSwapchains(stack.longs(swapchain));
            present.pImageIndices(stack.ints(imageIndex));
            int r = KHRSwapchain.vkQueuePresentKHR(presentQueue, present);
            return r == VK10.VK_SUCCESS || r == 1000001003 /* SUBOPTIMAL still presented */;
        }
    }

    private void ensureSemaphores(VulkanDevice device, int count) {
        if (acquireSemaphores.length == count) {
            return;
        }
        destroy(device);
        acquireSemaphores = new long[count];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSemaphoreCreateInfo sci = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
            LongBuffer p = stack.mallocLong(1);
            for (int i = 0; i < count; i++) {
                if (VK10.vkCreateSemaphore(device.vkDevice(), sci, null, p) != VK10.VK_SUCCESS) {
                    throw new IllegalStateException("vkCreateSemaphore(fg acquire) failed");
                }
                acquireSemaphores[i] = p.get(0);
            }
        }
        acquireCursor = 0;
    }

    /** Destroy the acquire-semaphore pool (device teardown / resize). */
    public void destroy(VulkanDevice device) {
        for (long sem : acquireSemaphores) {
            if (sem != 0L) {
                VK10.vkDestroySemaphore(device.vkDevice(), sem, null);
            }
        }
        acquireSemaphores = new long[0];
        acquireCursor = 0;
    }
}
