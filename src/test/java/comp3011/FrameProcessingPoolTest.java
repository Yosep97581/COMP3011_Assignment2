/*
 * Tests by Yosep Monier Nababan (a1939679), with assistance from OpenAI ChatGPT.
 * Check worker independence, frame ownership, ordering, backpressure and shutdown.
 */
package comp3011;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;

class FrameProcessingPoolTest {
    private final InfoVideo info = new InfoVideo("demo", 100, 2, 2,
            Frame.DEPTH_UBYTE, 1, 2, 24.0, 24, 41667, 0);

    private Frame frame(int value) {
        Frame frame = new Frame(2, 2, Frame.DEPTH_UBYTE, 1);
        ((ByteBuffer) frame.image[0]).put(0, (byte) value);
        return frame;
    }

    private FrameProcessingPool.Result waitForResult(FrameProcessingPool pool) throws Exception {
        long timeout = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        FrameProcessingPool.Result result;
        do {
            result = pool.pollResult();
            if (result != null) {
                return result;
            }
            Thread.sleep(5);
        } while (System.nanoTime() < timeout);
        fail("Timed out waiting for a processed frame");
        return null;
    }

    @Test
    void framesProcessOnDifferentWorkersAndDoNotOverwriteDecoderData() throws Exception {
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Set<String> threads = ConcurrentHashMap.newKeySet();
        AtomicInteger chains = new AtomicInteger();

        try (FrameProcessingPool pool = new FrameProcessingPool(info, () -> {
            chains.incrementAndGet();
            return List.of(new FrameProcessor() {
                @Override
                public void process(Frame frame, InfoFrame metadata) throws Exception {
                    threads.add(Thread.currentThread().getName());
                    started.countDown();
                    if (!release.await(2, TimeUnit.SECONDS)) {
                        throw new Exception("Other worker never started");
                    }
                    ((ByteBuffer) frame.image[0]).put(0, (byte) (metadata.frameNumber() + 10));
                }
            });
        }, 2, 2)) {
            try (Frame first = frame(3); Frame second = frame(4); Frame third = frame(5)) {
                assertTrue(pool.trySubmit(0, first, new InfoFrame(3, 0)));
                assertTrue(pool.trySubmit(1, second, new InfoFrame(4, 41667)));
                assertFalse(pool.trySubmit(2, third, new InfoFrame(5, 83334)),
                        "The in-flight limit must cover running and completed work");
                assertTrue(started.await(2, TimeUnit.SECONDS), "Both workers should work at once");
                release.countDown();
                FrameProcessingPool.Result r1 = waitForResult(pool);
                FrameProcessingPool.Result r2 = waitForResult(pool);
                try {
                    assertTrue(r1.succeeded());
                    assertTrue(r2.succeeded());
                    assertEquals(2, threads.size());
                    assertEquals(2, chains.get()); // A separate effect chain for each worker.
                    assertEquals(3, ((ByteBuffer) first.image[0]).get(0));
                    assertEquals(4, ((ByteBuffer) second.image[0]).get(0));
                    for (FrameProcessingPool.Result r : List.of(r1, r2)) {
                        assertEquals(r.info().frameNumber() + 10,
                                Byte.toUnsignedInt(((ByteBuffer) r.frame().image[0]).get(0)));
                    }
                } finally {
                    r1.frame().close();
                    r2.frame().close();
                }
            }
        } finally {
            release.countDown();
        }
    }

    @Test
    void effectsRunInOrderAndReportProcessingErrors() throws Exception {
        try (FrameProcessingPool pool = new FrameProcessingPool(info, () -> List.of(
                new FrameProcessor() {
                    @Override
                    public void process(Frame frame, InfoFrame metadata) {
                        ByteBuffer pixels = (ByteBuffer) frame.image[0];
                        pixels.put(0, (byte) (pixels.get(0) + 1));
                    }
                },
                new FrameProcessor() {
                    @Override
                    public void process(Frame frame, InfoFrame metadata) {
                        ByteBuffer pixels = (ByteBuffer) frame.image[0];
                        pixels.put(0, (byte) (pixels.get(0) * 2));
                    }
                }), 2, 2)) {
            try (Frame original = frame(7)) {
                assertTrue(pool.trySubmit(0, original, new InfoFrame(1, 0)));
                FrameProcessingPool.Result result = waitForResult(pool);
                try {
                    assertTrue(result.succeeded());
                    assertEquals(16, Byte.toUnsignedInt(((ByteBuffer) result.frame().image[0]).get(0)));
                    assertEquals(7, Byte.toUnsignedInt(((ByteBuffer) original.image[0]).get(0)));
                } finally {
                    result.frame().close();
                }
            }
        }
        try (FrameProcessingPool pool = new FrameProcessingPool(info, () -> List.of(
                new FrameProcessor() {
                    @Override
                    public void process(Frame frame, InfoFrame metadata) throws Exception {
                        throw new Exception("test error");
                    }
                }), 1, 1);
                Frame input = frame(1)) {
            assertTrue(pool.trySubmit(0, input, new InfoFrame(2, 0)));
            FrameProcessingPool.Result result = waitForResult(pool);
            try {
                assertFalse(result.succeeded());
                assertEquals("test error", result.error().getMessage());
            } finally {
                result.frame().close();
            }
        }
    }

    @Test
    void repeatedEffectNumbersRemainStableAcrossWorkerChains() {
        List<FrameEffect> effects = List.of(FrameEffect.SCRATCH, FrameEffect.FLICKER,
                FrameEffect.SCRATCH, FrameEffect.SCRATCH, FrameEffect.FLICKER);
        List<FrameProcessor> first = FrameEffect.createProcessors(effects);
        List<FrameProcessor> second = FrameEffect.createProcessors(effects);
        assertEquals(List.of(0, 0, 1, 2, 1), first.stream().map(p -> p.instanceIndex).toList());
        assertEquals(first.stream().map(p -> p.instanceIndex).toList(),
                second.stream().map(p -> p.instanceIndex).toList());
        assertNotSame(first.get(0), second.get(0));
    }


    @Test
    void realEffectsMatchTheSequentialOutputEvenWhenRepeated() throws Exception {
        try (Frame source = new Frame(8, 8, Frame.DEPTH_UBYTE, 3)) {
            ByteBuffer pixels = (ByteBuffer) source.image[0];
            for (int i = 0; i < pixels.capacity(); i++) {
                pixels.put(i, (byte) ((i * 17) % 256));
            }
            InfoVideo videoInfo = new InfoVideo("demo", 100, source.imageWidth,
                    source.imageHeight, source.imageDepth, source.imageChannels,
                    source.imageStride, 24.0, 24, 41667, 0);
            List<FrameEffect> effects = List.of(FrameEffect.FLICKER,
                    FrameEffect.BLACK_AND_WHITE, FrameEffect.FLICKER);
            InfoFrame metadata = new InfoFrame(25, 1_000_000);
            try (Frame expected = source.clone();
                    FrameProcessingPool pool = new FrameProcessingPool(videoInfo, effects, 2, 2)) {
                for (FrameProcessor processor : FrameEffect.createProcessors(effects)) {
                    processor.initialise(videoInfo);
                    processor.process(expected, metadata);
                }
                assertTrue(pool.trySubmit(0, source, metadata));
                assertTrue(pool.trySubmit(1, source, metadata));
                for (int i = 0; i < 2; i++) {
                    FrameProcessingPool.Result result = waitForResult(pool);
                    try {
                        assertTrue(result.succeeded());
                        ByteBuffer actual = (ByteBuffer) result.frame().image[0];
                        ByteBuffer baseline = (ByteBuffer) expected.image[0];
                        for (int byteIndex = 0; byteIndex < baseline.capacity(); byteIndex++) {
                            assertEquals(baseline.get(byteIndex), actual.get(byteIndex));
                        }
                    } finally {
                        result.frame().close();
                    }
                }
            }
        }
    }

    @Test
    void capacityIncludesCompletedFramesUntilTheyAreCollected() throws Exception {
        try (FrameProcessingPool pool = new FrameProcessingPool(info, List.of(), 1, 1);
                Frame input = frame(9)) {
            assertTrue(pool.hasCapacity());
            assertTrue(pool.trySubmit(0, input, new InfoFrame(0, 0)));
            assertFalse(pool.hasCapacity());
            // Even after the worker finishes, the result occupies a slot.
            Thread.sleep(25);
            assertFalse(pool.hasCapacity());
            FrameProcessingPool.Result result = waitForResult(pool);
            try {
                assertTrue(result.succeeded());
                assertTrue(pool.hasCapacity());
            } finally {
                result.frame().close();
            }
        }
    }

    @Test
    void stoppedPoolRejectsAdditionalFrames() {
        FrameProcessingPool pool = new FrameProcessingPool(info, List.of(), 2, 2);
        pool.close();
        try (Frame input = frame(4)) {
            assertFalse(pool.trySubmit(0, input, new InfoFrame(0, 0)));
        }
    }

    @Test
    void returnsFramesInOrderEvenWhenSecondWorkerFinishesFirst() throws Exception {
        CountDownLatch firstWorkerStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstWorker = new CountDownLatch(1);
        CountDownLatch secondWorkerFinished = new CountDownLatch(1);
        try (FrameProcessingPool pool = new FrameProcessingPool(info, () -> List.of(
                new FrameProcessor() {
                    @Override
                    public void process(Frame frame, InfoFrame metadata) throws Exception {
                        if (metadata.frameNumber() == 0) {
                            firstWorkerStarted.countDown();
                            if (!releaseFirstWorker.await(2, TimeUnit.SECONDS)) {
                                throw new Exception("First frame was never released");
                            }
                        } else if (metadata.frameNumber() == 1) {
                            secondWorkerFinished.countDown();
                        }
                    }
                }), 2, 2);
                Frame first = frame(10);
                Frame second = frame(20);
                Frame third = frame(30)) {
            try {
                assertTrue(pool.trySubmit(0, first, new InfoFrame(0, 0)));
                assertTrue(firstWorkerStarted.await(2, TimeUnit.SECONDS));
                assertTrue(pool.trySubmit(1, second, new InfoFrame(1, 41667)));
                assertTrue(secondWorkerFinished.await(2, TimeUnit.SECONDS));

                // Frame 1 may be finished, but cannot be delivered before frame 0.
                assertNull(pool.pollResult());
                assertFalse(pool.trySubmit(2, third, new InfoFrame(2, 83334)),
                        "Waiting results must still count towards the memory limit");

                releaseFirstWorker.countDown();
                FrameProcessingPool.Result result0 = waitForResult(pool);
                try {
                    assertEquals(0L, result0.sequence());
                    assertTrue(result0.succeeded());
                } finally {
                    result0.frame().close();
                }

                // Collecting frame 0 frees space for the next submitted frame.
                assertTrue(pool.trySubmit(2, third, new InfoFrame(2, 83334)));
                FrameProcessingPool.Result result1 = waitForResult(pool);
                FrameProcessingPool.Result result2 = waitForResult(pool);
                try {
                    assertEquals(1L, result1.sequence());
                    assertEquals(2L, result2.sequence());
                } finally {
                    result1.frame().close();
                    result2.frame().close();
                }
            } finally {
                releaseFirstWorker.countDown();
            }
        }
    }

    @Test
    void requiresConsecutiveSequenceNumbersAndAllowsRetryAfterBackpressure() throws Exception {
        try (FrameProcessingPool pool = new FrameProcessingPool(info, List.of(), 1, 1);
                Frame first = frame(1);
                Frame next = frame(2)) {
            assertThrows(IllegalArgumentException.class,
                    () -> pool.trySubmit(-1, first, new InfoFrame(0, 0)));
            assertThrows(IllegalArgumentException.class,
                    () -> pool.trySubmit(2, first, new InfoFrame(0, 0)));
            assertTrue(pool.trySubmit(0, first, new InfoFrame(0, 0)));
            assertThrows(IllegalArgumentException.class,
                    () -> pool.trySubmit(0, first, new InfoFrame(0, 0)));
            assertFalse(pool.trySubmit(1, next, new InfoFrame(1, 41667)));

            FrameProcessingPool.Result result = waitForResult(pool);
            try {
                assertEquals(0L, result.sequence());
            } finally {
                result.frame().close();
            }
            assertTrue(pool.trySubmit(1, next, new InfoFrame(1, 41667)));
            result = waitForResult(pool);
            try {
                assertEquals(1L, result.sequence());
            } finally {
                result.frame().close();
            }
        }
    }

    @Test
    void closingPoolRejectsWorkAndNeverDeliversOldFrames() throws Exception {
        CountDownLatch enteredWorker = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        FrameProcessingPool pool = new FrameProcessingPool(info, () -> List.of(
                new FrameProcessor() {
                    @Override
                    public void process(Frame frame, InfoFrame metadata) throws Exception {
                        enteredWorker.countDown();
                        releaseWorker.await(2, TimeUnit.SECONDS);
                    }
                }), 1, 1);
        try (Frame first = frame(1); Frame next = frame(2)) {
            assertTrue(pool.trySubmit(0, first, new InfoFrame(0, 0)));
            assertTrue(enteredWorker.await(2, TimeUnit.SECONDS));
            pool.close();
            pool.close(); // Shutdown should be safe to call more than once.
            assertFalse(pool.trySubmit(1, next, new InfoFrame(1, 41667)));
            assertNull(pool.pollResult());
        } finally {
            releaseWorker.countDown();
            pool.close();
        }
    }

}
