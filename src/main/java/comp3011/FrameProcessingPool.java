/*
 * Based on starter code supplied for Adelaide University COMP3011 Assignment 2.
 * New class by Yosep Monier Nababan (a1939679), with assistance from OpenAI ChatGPT.
 *
 * Changes: process independent video frames on a bounded pool of background threads.
 */
package comp3011;

import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.bytedeco.javacv.Frame;

/**
 * Processes different frames in parallel, but applies each frame's effects in
 * the original command-line order. The decoder and JavaFX GUI do not belong here.
 *
 * The caller owns an input frame. We clone it before handing it to a worker,
 * because FFmpeg can reuse its frame buffers on the next decode.
 */
public final class FrameProcessingPool implements AutoCloseable {
    public record Result(long sequence, Frame frame, InfoFrame info, Exception error) {
        public boolean succeeded() {
            return error == null;
        }
    }

    private final ThreadPoolExecutor executor;
    private final ThreadLocal<List<FrameProcessor>> workerProcessors;
    private final Semaphore inFlight;
    private final Queue<Result> completed = new ConcurrentLinkedQueue<>();
    private volatile boolean closed;

    public FrameProcessingPool(InfoVideo videoInfo, List<FrameEffect> effects,
            int workers, int maxInFlight) {
        this(videoInfo, () -> FrameEffect.createProcessors(List.copyOf(effects)),
                workers, maxInFlight);
    }

    // Package-private constructor lets tests supply slow or failing processors.
    FrameProcessingPool(InfoVideo videoInfo, Supplier<List<FrameProcessor>> processorFactory,
            int workers, int maxInFlight) {
        Objects.requireNonNull(videoInfo);
        Objects.requireNonNull(processorFactory);
        if (workers < 1 || maxInFlight < 1) {
            throw new IllegalArgumentException("Workers and in-flight limit must be positive");
        }
        inFlight = new Semaphore(maxInFlight);
        AtomicInteger nextThreadId = new AtomicInteger();
        ThreadFactory threadFactory = job -> {
            Thread thread = new Thread(job, "video-effect-worker-" + nextThreadId.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        // The semaphore also counts finished results that have not been collected.
        // This limits memory, not just the executor's waiting task queue.
        executor = new ThreadPoolExecutor(workers, workers, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(maxInFlight), threadFactory,
                new ThreadPoolExecutor.AbortPolicy());
        workerProcessors = ThreadLocal.withInitial(() -> {
            try {
                List<FrameProcessor> processors = processorFactory.get();
                for (FrameProcessor processor : processors) {
                    processor.initialise(videoInfo);
                }
                return processors;
            } catch (Exception e) {
                throw new IllegalStateException("Could not initialise frame effects", e);
            }
        });
    }

    /**
     * Try to submit a frame without waiting for space. Return false when the
     * pool is full or stopped; the decoder should retry later, not lose a frame.
     */
    public synchronized boolean trySubmit(long sequence, Frame source, InfoFrame info) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(info);
        if (closed || !inFlight.tryAcquire()) {
            return false;
        }

        Frame copy = null;
        try {
            copy = source.clone(); // Deep copy, not another view of the FFmpeg buffer.
            executor.execute(new ProcessingTask(sequence, copy, info));
            return true;
        } catch (RejectedExecutionException e) {
            if (copy != null) {
                copy.close();
            }
            inFlight.release();
            return false;
        } catch (RuntimeException e) {
            if (copy != null) {
                copy.close();
            }
            inFlight.release();
            throw e;
        }
    }

    /**
     * Non-blocking result retrieval. Results can arrive out of order; the
     * playback model must arrange them by sequence before displaying them.
     * The receiver must close the returned frame when finished with it.
     */
    public Result pollResult() {
        Result result = completed.poll();
        if (result != null) {
            inFlight.release();
        }
        return result;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        // Tasks already running may need time to finish. They check 'closed'
        // before publishing, so their old results cannot enter the next session.
        for (Runnable pending : executor.shutdownNow()) {
            if (pending instanceof ProcessingTask task) {
                task.frame.close();
            }
        }
        Result result;
        while ((result = completed.poll()) != null) {
            result.frame().close();
        }
    }

    private final class ProcessingTask implements Runnable {
        private final long sequence;
        private final Frame frame;
        private final InfoFrame info;

        ProcessingTask(long sequence, Frame frame, InfoFrame info) {
            this.sequence = sequence;
            this.frame = frame;
            this.info = info;
        }

        @Override
        public void run() {
            Exception failure = null;
            try {
                if (!closed) {
                    // Each thread initialises and reuses its OWN effect instances.
                    for (FrameProcessor processor : workerProcessors.get()) {
                        if (closed || Thread.currentThread().isInterrupted()) {
                            break;
                        }
                        processor.process(frame, info);
                    }
                }
            } catch (Exception e) {
                failure = e;
            }
            // Publication and close must not race, or a completed frame could
            // be added after close() had already drained the queue.
            synchronized (FrameProcessingPool.this) {
                if (closed) {
                    frame.close();
                } else {
                    completed.add(new Result(sequence, frame, info, failure));
                }
            }
        }
    }
}
