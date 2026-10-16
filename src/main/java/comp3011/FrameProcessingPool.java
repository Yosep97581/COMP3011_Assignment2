/*
 * Based on starter code supplied for Adelaide University COMP3011 Assignment 2.
 * New class by Yosep Monier Nababan (a1939679), with assistance from OpenAI ChatGPT.
 *
 * Changes: process independent video frames on a bounded pool of background threads.
 * Deliver frames in sequence and keep the memory limit effective even when
 * workers finish out of order.
 */
package comp3011;

import java.util.List;
import java.util.Objects;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
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
    // Results stay here until all earlier frames have been returned. Keeping
    // them inside the pool means completed-but-waiting frames still count
    // towards the in-flight memory limit.
    private final NavigableMap<Long, Result> completed = new TreeMap<>();
    private long nextSequenceToSubmit;
    private long nextSequenceToDeliver;
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
     * Submit frames with consecutive sequence numbers starting at zero.
     * Return false if full so the decoder can retry the SAME frame later.
     * Start a fresh pool and numbering sequence after a seek/restart.
     */
    public synchronized boolean trySubmit(long sequence, Frame source, InfoFrame info) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(info);
        if (closed) {
            return false;
        }
        if (sequence != nextSequenceToSubmit) {
            throw new IllegalArgumentException("Expected frame sequence "
                    + nextSequenceToSubmit + " but got " + sequence);
        }
        if (!inFlight.tryAcquire()) {
            return false;
        }

        Frame copy = null;
        try {
            copy = source.clone(); // Deep copy, not another view of the FFmpeg buffer.
            executor.execute(new ProcessingTask(sequence, copy, info));
            nextSequenceToSubmit++;
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
     * Return only the next frame in decoding order, without blocking the GUI.
     * A later completed frame waits here until earlier frames finish.
     * The caller must close every returned frame after converting/using it.
     */
    public synchronized Result pollResult() {
        if (closed) {
            return null;
        }
        Result result = completed.remove(nextSequenceToDeliver);
        if (result != null) {
            nextSequenceToDeliver++;
            // Do not free the permit while a completed frame is still waiting
            // for an earlier frame. Otherwise buffering could grow unchecked.
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
        for (Result result : completed.values()) {
            result.frame().close();
        }
        completed.clear();
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
                    completed.put(sequence, new Result(sequence, frame, info, failure));
                }
            }
        }
    }
}
