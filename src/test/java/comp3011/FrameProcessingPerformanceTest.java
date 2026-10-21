/*
 * Tests by Yosep Monier Nababan (a1939679), with assistance from OpenAI ChatGPT.
 * Compare sequential effect processing with the concurrent pool on identical
 * generated frames. We check the outputs and report timing, not a fixed speedup:
 * actual speed depends on the machine and current workload.
 */
package comp3011;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;

class FrameProcessingPerformanceTest {
    private static final int WIDTH = 640;
    private static final int HEIGHT = 360;
    private static final int FRAME_COUNT = 36;
    private static final List<FrameEffect> EFFECTS = List.of(
            FrameEffect.BLACK_AND_WHITE, FrameEffect.FLICKER,
            FrameEffect.VIGNETTE, FrameEffect.YELLOW, FrameEffect.FLICKER);

    @Test
    void comparesSequentialAndParallelEffectProcessing() throws Exception {
        int workers = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
        try (Frame source = new Frame(WIDTH, HEIGHT, Frame.DEPTH_UBYTE, 3)) {
            ByteBuffer pixels = (ByteBuffer) source.image[0];
            for (int i = 0; i < pixels.capacity(); i++) {
                pixels.put(i, (byte) (i * 31 + 17));
            }
            InfoVideo info = new InfoVideo("performance-test", FRAME_COUNT, WIDTH, HEIGHT,
                    source.imageDepth, source.imageChannels, source.imageStride,
                    24.0, 24, 41_667, 0);
            long[] sequentialChecksums = new long[FRAME_COUNT];

            List<FrameProcessor> sequentialEffects = FrameEffect.createProcessors(EFFECTS);
            for (FrameProcessor effect : sequentialEffects) {
                effect.initialise(info);
            }
            long startSequentialNs = System.nanoTime();
            for (int i = 0; i < FRAME_COUNT; i++) {
                try (Frame copy = source.clone()) {
                    InfoFrame frameInfo = new InfoFrame(i, i * 41_667L);
                    for (FrameProcessor effect : sequentialEffects) {
                        effect.process(copy, frameInfo);
                    }
                    sequentialChecksums[i] = checksum(copy);
                }
            }
            long sequentialNs = System.nanoTime() - startSequentialNs;

            long startParallelNs = System.nanoTime();
            int submitted = 0;
            int collected = 0;
            long deadlineNs = startParallelNs + TimeUnit.SECONDS.toNanos(90);
            try (FrameProcessingPool pool = new FrameProcessingPool(info, EFFECTS, workers, 8)) {
                while (collected < FRAME_COUNT) {
                    assertTrue(System.nanoTime() < deadlineNs,
                            "Timed out waiting for processed frames");
                    if (submitted < FRAME_COUNT && pool.hasCapacity()
                            && pool.trySubmit(submitted, source,
                                    new InfoFrame(submitted, submitted * 41_667L))) {
                        submitted++;
                    }
                    FrameProcessingPool.Result result = pool.pollResult();
                    if (result != null) {
                        try (Frame frame = result.frame()) {
                            assertTrue(result.succeeded(), "A worker failed: " + result.error());
                            assertEquals(collected, result.sequence(), "Frames must remain in order");
                            assertEquals(sequentialChecksums[collected], checksum(frame),
                                    "Frame pixels differ from the sequential implementation");
                        }
                        collected++;
                    } else {
                        Thread.sleep(1);
                    }
                }
            }
            long parallelNs = System.nanoTime() - startParallelNs;
            double sequentialMs = sequentialNs / 1_000_000.0;
            double parallelMs = parallelNs / 1_000_000.0;
            System.out.printf(
                    "Effect-processing comparison (%dx%d, %d frames, %d workers): "
                    + "sequential %.1f ms, parallel %.1f ms, speedup %.2fx%n",
                    WIDTH, HEIGHT, FRAME_COUNT, workers, sequentialMs, parallelMs,
                    sequentialMs / parallelMs);
            // Never demand a hard speedup: small/contended machines can be slower
            // even when the concurrent results are correct.
        }
    }

    private long checksum(Frame frame) {
        ByteBuffer bytes = ((ByteBuffer) frame.image[0]).duplicate();
        long hash = 0x811c9dc5L;
        for (int i = 0; i < bytes.capacity(); i++) {
            hash ^= Byte.toUnsignedInt(bytes.get(i));
            hash = (hash * 0x01000193L) & 0xffffffffL;
        }
        return hash;
    }
}
