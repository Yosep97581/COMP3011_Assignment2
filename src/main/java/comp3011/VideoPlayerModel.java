/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Yosep Monier Nababan (a1939679), with assistance from OpenAI ChatGPT
 *
 * Copyright 2026 Simon Ratcliffe
 *
 * Changes: create the ordered list of visual processors from the supplied
 * effect configuration rather than from hard-coded commented-out lines.
 * Assign stable indexes for repeated effects (used by parallel workers later).
 * Route video frames through the concurrent worker pool while keeping playback
 * controls, audio scheduling and decoder ownership on the JavaFX thread for now.
 * Avoid decoding while the bounded processing pipeline is full, and print
 * comparable timing summaries for the original and parallel modes.
 */
package comp3011;

import java.io.File;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.JavaFXFrameConverter;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.image.Image;

/**
 * The model part of the video player model-view-controller architecture.
 *
 * <p>
 * Owns the FFmpeg decoder, playback clock, seeking logic and audio scheduling.
 * Video effects can run on background workers while the JavaFX thread collects
 * the finished frames in their original order. It publishes playback state through
 * callbacks supplied by its owning {@link VideoPlayerController}, keeping it
 * decoupled from the JavaFX view.
 * </p>
 */
public class VideoPlayerModel {
    private static final long NO_SEEK_REQUEST = -1; // Sentinel value used when no seek position is active.
    private static final long FIVE_SECONDS_US = 5_000_000L;
    private static final long AUDIO_LEAD_NS = 30_000_000L;
    // Keep a few frames in flight without letting HD frame copies use unlimited memory.
    private static final int EFFECT_WORKERS = Math.max(1,
            Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
    private static final int MAX_IN_FLIGHT = Math.max(2, EFFECT_WORKERS * 2);
    private static final int DECODE_STEPS_PER_PULSE = 4;

    // Keep the original path for comparison, and skip worker overhead if no
    // visual effects were selected at all.
    private final boolean singleThreaded;
    private final List<FrameEffect> selectedEffects;

    private final List<FrameProcessor> frameProcessors;
    private final Queue<PendingAudio> pendingAudio = new ArrayDeque<>();
    private final BiConsumer<Integer, Integer> videoSizeChangedHandler;
    private final Consumer<Image> frameReadyHandler;
    private final Consumer<String> statusChangedHandler;
    private final BiConsumer<Boolean, Boolean> playbackStateChangedHandler;
    private final Consumer<Boolean> audioOutputStateChangedHandler;

    // This is the critical wiring that allows the framework (JavaFX) to call
    // into our model logic every time it goes around its event loop. Every GUI
    // system has an event loop so that button clicks, key presses and window
    // resizes can be responded to. Most offer ways of running additional logic
    // in either idle time (when there are no pending UI events to process) or
    // on a regular heart beat, such as with this JavaFX AnimationTimer. Here
    // we just create a little anonymous local subclass and override the handle
    // method to call the method we want run every heart beat.
    private final AnimationTimer playbackTimer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            pumpPlayback(now);
        }
    };

    private File videoFile; // Path to the media that comes from the command line
    private FFmpegFrameGrabber grabber; // This is the 3rd party video media decoder. It deals in JavaCV Frame objects.
    private JavaFXFrameConverter converter; // Takes Frame objects to JavaFX Image objects, which can be put on screen.
    private AudioPlayer audioPlayer; // This is ours. It has some real time buffering smarts.
    private PreparedFrame preparedFrame; // Ours, but is just an Image with some meta-data added.
    private boolean prepareNextFrameQueued; // True only when the event loop needs to call our prepareNextFrame method.
    private boolean playbackOpen; // True when playback is happening.
    private boolean pauseRequested;
    private boolean pausedPreviewPending; // Show a single preview after a paused seek.
    private boolean audioOutputEnabled;
    private boolean audioAvailable;
    private boolean frameProcessorsInitialised;
    private FrameProcessingPool processingPool;
    // When the pool is full, this frame must be submitted before grabbing again:
    // FFmpeg can reuse its buffers on the next grab.
    private Frame waitingForSubmission;
    private InfoFrame waitingFrameInfo;
    private long submittedFrames;
    private long collectedFrames;
    private boolean reachedVideoEnd;
    private long framesDisplayed;
    private long latePreparedFrames;
    private long totalFrameHeadroomUs;
    private long currentTimestampUs;
    private long videoDurationUs = NO_SEEK_REQUEST;
    private int videoFrameDurationUs;
    private double frameRate;
    private int intFrameRate;
    private int totalVideoFrames;
    private long firstTimestampUs = NO_SEEK_REQUEST;
    private long logicalPlaybackBaseUs;
    private long playbackStartNs;
    private long pauseStartedNs;
    private long relativeSeekBaseUs = NO_SEEK_REQUEST;

    public VideoPlayerModel(
            boolean audioEnabled,
            List<FrameEffect> effects,
            BiConsumer<Integer, Integer> videoSizeChangedHandler,
            Consumer<Image> frameReadyHandler,
            Consumer<String> statusChangedHandler,
            BiConsumer<Boolean, Boolean> playbackStateChangedHandler,
            Consumer<Boolean> audioOutputStateChangedHandler) {
        audioOutputEnabled = audioEnabled;
        this.videoSizeChangedHandler = videoSizeChangedHandler;
        this.frameReadyHandler = frameReadyHandler;
        this.statusChangedHandler = statusChangedHandler;
        this.playbackStateChangedHandler = playbackStateChangedHandler;
        this.audioOutputStateChangedHandler = audioOutputStateChangedHandler;
        // Each command-line occurrence creates a separate effect instance.
        // Keeping the order is important because effects modify the same frame.
        this.selectedEffects = List.copyOf(effects);
        this.singleThreaded = Boolean.getBoolean("video.singleThreaded")
                || Boolean.parseBoolean(System.getenv("VIDEO_SINGLE_THREADED"))
                || selectedEffects.isEmpty();
        this.frameProcessors = FrameEffect.createProcessors(selectedEffects);
    }

    public void play(File file) {
        videoFile = file;
        videoDurationUs = NO_SEEK_REQUEST;
        videoFrameDurationUs = 0;
        startPlayback(0, false, NO_SEEK_REQUEST);
    }

    public void startOver() {
        if (videoFile == null) {
            return;
        }

        seekTo(0);
    }

    public void backFiveSeconds() {
        seekRelative(-FIVE_SECONDS_US);
    }

    public void forwardFiveSeconds() {
        seekRelative(FIVE_SECONDS_US);
    }

    public void togglePause() {
        if (!playbackOpen) {
            if (videoFile != null) {
                startPlayback(displayableSeekTimestamp(currentTimestampUs), false, currentTimestampUs);
            }
            return;
        }

        pauseRequested = !pauseRequested;
        pausedPreviewPending = false;
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        if (pauseRequested) {
            pauseStartedNs = System.nanoTime();
            flushAudioOutput();
        } else {
            resumePlaybackClock(System.nanoTime());
        }

        notifyPlaybackStateChanged();
    }

    public void toggleAudioOutput() {
        audioOutputEnabled = !audioOutputEnabled;
        pendingAudio.clear();
        flushAudioOutput();
        notifyAudioOutputStateChanged();
    }

    public boolean isAudioOutputEnabled() {
        return audioOutputEnabled;
    }

    public void stopPlayback() {
        closePlaybackResources();
        currentTimestampUs = 0;
        pauseRequested = false;
        notifyStatusChanged("Stopped");
        notifyFrameReady(null);
        notifyPlaybackStateChanged();
    }

    public void shutdown() {
        closePlaybackResources();
    }

    private void seekRelative(long offsetUs) {
        if (videoFile == null) {
            return;
        }

        long baseTimestampUs = relativeSeekBaseUs != NO_SEEK_REQUEST
                ? relativeSeekBaseUs
                : currentTimestampUs;
        seekTo(baseTimestampUs + offsetUs);
    }

    private void seekTo(long timestampUs) {
        long logicalTimestampUs = clampSeekTimestamp(timestampUs);
        long grabTimestampUs = displayableSeekTimestamp(logicalTimestampUs);

        if (!playbackOpen) {
            startPlayback(grabTimestampUs, pauseRequested, logicalTimestampUs);
            return;
        }

        try {
            pausedPreviewPending = pauseRequested;
            // Stop the old workers before a seek; their results belong to
            // the previous playback position and must never be displayed.
            grabber.setTimestamp(grabTimestampUs);
            resetPlaybackClock(logicalTimestampUs);
            flushAudioOutput();
            prepareNextFrame();
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private void startPlayback(long startTimestampUs, boolean initiallyPaused, long initialRelativeSeekBaseUs) {
        closePlaybackResources();

        pauseRequested = initiallyPaused;
        pausedPreviewPending = initiallyPaused;
        currentTimestampUs = initialRelativeSeekBaseUs != NO_SEEK_REQUEST
                ? initialRelativeSeekBaseUs
                : startTimestampUs;
        relativeSeekBaseUs = initialRelativeSeekBaseUs;

        notifyStatusChanged(videoFile.getName());

        try {
            openPlaybackResources(startTimestampUs);
            resetPlaybackClock(currentTimestampUs);
            playbackOpen = true;
            playbackTimer.start();
            prepareNextFrame();
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private void openPlaybackResources(long startTimestampUs) throws Exception {
        converter = new JavaFXFrameConverter();
        grabber = new FFmpegFrameGrabber(videoFile);
        grabber.setImageMode(FrameGrabber.ImageMode.COLOR);
        grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
        grabber.start();

        notifyVideoSizeChanged(grabber.getImageWidth(), grabber.getImageHeight());

        frameRate = grabber.getFrameRate();
        intFrameRate = (int) Math.round(frameRate);
        videoFrameDurationUs = frameRate > 0
                ? (int) Math.round(1_000_000.0 / frameRate)
                : 0;
        totalVideoFrames = grabber.getLengthInVideoFrames();

        long durationUs = grabber.getLengthInTime();
        videoDurationUs = durationUs > 0
                ? durationUs
                : NO_SEEK_REQUEST;

        audioAvailable = grabber.hasAudio();
        audioPlayer = new AudioPlayer();
        if (audioAvailable) {
            audioPlayer.open(grabber.getSampleRate(), grabber.getAudioChannels());
        }

        if (startTimestampUs > 0) {
            grabber.setTimestamp(startTimestampUs);
        }
    }

    private void resetPlaybackClock(long logicalTimestampUs) {
        pendingAudio.clear();
        preparedFrame = null;
        resetProcessingPipeline();
        currentTimestampUs = logicalTimestampUs;
        relativeSeekBaseUs = logicalTimestampUs;
        firstTimestampUs = NO_SEEK_REQUEST;
        framesDisplayed = 0;
        latePreparedFrames = 0;
        totalFrameHeadroomUs = 0;
        logicalPlaybackBaseUs = logicalTimestampUs;
        playbackStartNs = 0;
        pauseStartedNs = pauseRequested ? System.nanoTime() : 0;
    }

    private void resumePlaybackClock(long now) {
        if (pauseStartedNs > 0 && playbackStartNs > 0) {
            long pausedDuration = now - pauseStartedNs;
            playbackStartNs += pausedDuration;
            if (preparedFrame != null) {
                PreparedFrame frame = preparedFrame;
                preparedFrame = new PreparedFrame(frame.image(), frame.frameNumber(),
                        frame.mediaTimestampUs(), frame.logicalTimestampUs(),
                        frame.targetTimeNs() + pausedDuration, frame.preparedAtNs());
            }
        }
        pauseStartedNs = 0;
    }

    private void prepareNextFrame() {
        if (!playbackOpen) {
            return;
        }

        try {
            if (singleThreaded) {
                if (preparedFrame == null) {
                    preparedFrame = readNextVideoFrame();
                }
            } else {
                submitOneVideoFrame();
            }
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    /**
     * Feed one video frame per call, so we don't monopolise JavaFX just to
     * fill the pool. The decoder stays on this thread for this milestone.
     * A later performance step can move decoding to its own thread.
     */
    private void submitOneVideoFrame() throws Exception {
        if (reachedVideoEnd) {
            return;
        }
        if (waitingForSubmission != null) {
            submitWaitingFrame();
            return;
        }
        // Check *before* calling FFmpeg. The original code decoded one more
        // frame even when all worker slots were occupied. That wasted GUI time
        // and made us hold a decoder buffer until space became available.
        if (processingPool != null && !processingPool.hasCapacity()) {
            return;
        }

        // Audio-only packets may come between video packets. Limit the work
        // per GUI pulse so a stretch of audio packets cannot freeze the GUI.
        for (int i = 0; i < DECODE_STEPS_PER_PULSE; i++) {
            Frame frame = grabFrame();
            if (frame == null) {
                reachedVideoEnd = true;
                return;
            }

            long timestampUs = grabber.getTimestamp();
            if (audioAvailable && frame.samples != null) {
                queueAudio(timestampUs, frame);
            }
            if (frame.image == null) {
                continue;
            }

            if (processingPool == null) {
                InfoVideo videoInfo = new InfoVideo(
                        mediaName(videoFile), totalVideoFrames,
                        frame.imageWidth, frame.imageHeight, frame.imageDepth,
                        frame.imageChannels, frame.imageStride, frameRate,
                        intFrameRate, videoFrameDurationUs, grabber.getPixelFormat());
                processingPool = new FrameProcessingPool(videoInfo, selectedEffects,
                        EFFECT_WORKERS, MAX_IN_FLIGHT);
            }

            waitingForSubmission = frame;
            waitingFrameInfo = new InfoFrame(grabber.getFrameNumber(), timestampUs);
            submitWaitingFrame();
            return;
        }
    }

    private void submitWaitingFrame() {
        if (processingPool.trySubmit(submittedFrames, waitingForSubmission, waitingFrameInfo)) {
            submittedFrames++;
            waitingForSubmission = null;
            waitingFrameInfo = null;
        }
    }

    /** Collect results only in decoding order, without waiting for a worker. */
    private void collectProcessedFrame(long now) {
        if (preparedFrame != null || processingPool == null) {
            return;
        }

        FrameProcessingPool.Result result = processingPool.pollResult();
        if (result == null) {
            return;
        }
        collectedFrames++;
        try (Frame frame = result.frame()) {
            if (!result.succeeded()) {
                handlePlaybackError(result.error());
                return;
            }

            long timestampUs = result.info().timestampUs();
            if (firstTimestampUs == NO_SEEK_REQUEST) {
                // Start the clock when the first *processed* frame is ready.
                // Otherwise the pipeline's initial buffering looks like late frames.
                firstTimestampUs = timestampUs;
                playbackStartNs = now;
                if (pauseRequested) {
                    pauseStartedNs = now;
                }
            }
            long relativeUs = Math.max(0L, timestampUs - firstTimestampUs);
            preparedFrame = new PreparedFrame(
                    converter.convert(frame), result.info().frameNumber(),
                    timestampUs, logicalPlaybackBaseUs + relativeUs,
                    playbackStartNs + relativeUs * 1_000L, System.nanoTime());
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    /** Cancel and discard all work from the previous playback/seek position. */
    private void resetProcessingPipeline() {
        if (processingPool != null) {
            processingPool.close();
            processingPool = null;
        }
        waitingForSubmission = null;
        waitingFrameInfo = null;
        submittedFrames = 0;
        collectedFrames = 0;
        reachedVideoEnd = false;
        frameProcessorsInitialised = false;
        prepareNextFrameQueued = false;
    }

    private PreparedFrame readNextVideoFrame() throws Exception {
        while (playbackOpen) {
            Frame frame = grabFrame();
            if (frame == null) {
                finishPlayback();
                return null;
            }

            long timestampUs = grabber.getTimestamp();
            if (audioAvailable && frame.samples != null) {
                queueAudio(timestampUs, frame);
            }

            if (frame.image == null) {
                continue;
            }

            if (!frameProcessorsInitialised) {
                initialiseFrameProcessors(new InfoVideo(
                        mediaName(videoFile),
                        totalVideoFrames,
                        frame.imageWidth,
                        frame.imageHeight,
                        frame.imageDepth,
                        frame.imageChannels,
                        frame.imageStride,
                        frameRate,
                        intFrameRate,
                        videoFrameDurationUs,
                        grabber.getPixelFormat()));
                frameProcessorsInitialised = true;
            }

            if (firstTimestampUs == NO_SEEK_REQUEST) {
                firstTimestampUs = timestampUs;
                playbackStartNs = System.nanoTime();
                if (pauseRequested) {
                    pauseStartedNs = playbackStartNs;
                }
            }

            int frameNumber = grabber.getFrameNumber();
            InfoFrame info = new InfoFrame(frameNumber, timestampUs);
            processFrame(frame, info);

            Image image = converter.convert(frame);
            long relativeTimestampUs = Math.max(0, timestampUs - firstTimestampUs);
            long logicalTimestampUs = logicalPlaybackBaseUs + relativeTimestampUs;
            long targetTimeNs = playbackStartNs + relativeTimestampUs * 1_000L;

            return new PreparedFrame(
                    image,
                    frameNumber,
                    timestampUs,
                    logicalTimestampUs,
                    targetTimeNs,
                    System.nanoTime());
        }

        return null;
    }

    // This is called on a heart beat by the GUI thread. We want to be co-operative here by (a) not blocking, and (b)
    // getting our required work out of the way quickly so that we can return control flow to the JavaFX event loop for
    // handling user interaction with the GUI and rendering. We have three jobs: (1) keep audio flowing if sound is on,
    // (2) display a frame if it is due and (3) prepare the next frame if we're in the window after the previous frame
    // has gone to the display. We don't buffer frames here, just handling them one at a time. Not a great architecture,
    // living on the edge a bit, but can't do much better on a single thread.
    private void pumpPlayback(long now) {
        if (!playbackOpen) {
            return;
        }
        if (!singleThreaded) {
            pumpConcurrentPlayback(now);
            return;
        }

        // Task (1)
        writeDueAudio(now);

        // Task (2)
        if (preparedFrame != null && preparedFrame.targetTimeNs() <= now) {
            // We have a prepared frame ready to go and it is due (or just past due!) so get it up on screen ASAP!
            displayPreparedFrame(now);
        }

        // Task (3). We'll try and be nice to the GUI event loop here by queueing the prepareNextFrame call rather than
        // hogging the thread and doing it here, hence the tricky callback and use of prepareNextFrameQueued. Feel free
        // to replace this whole block with simple linear control flow logic
        // such as: if (!pauseRequested && preparedFrame == null) prepareNextFrame();
        // to compare.
        if (!pauseRequested && preparedFrame == null && !prepareNextFrameQueued) {
            prepareNextFrameQueued = true;
            Platform.runLater(() -> {
                prepareNextFrameQueued = false;

                if (!pauseRequested) {
                    prepareNextFrame();
                }
            });
        }
    }

    /**
     * The GUI only polls finished frames; it never waits for a processing worker.
     * Decoding is still cooperative in this stage and is tackled separately.
     */
    private void pumpConcurrentPlayback(long now) {
        writeDueAudio(now);
        collectProcessedFrame(now);
        if (!playbackOpen) {
            return; // A worker may have reported an error.
        }
        if (preparedFrame != null && preparedFrame.targetTimeNs() <= now
                && (!pauseRequested || pausedPreviewPending)) {
            displayPreparedFrame(now);
            pausedPreviewPending = false;
        }

        if (!reachedVideoEnd && (!pauseRequested || submittedFrames == 0)) {
            prepareNextFrame();
        }

        // At EOF, wait for the submitted frames and any queued audio to drain.
        if (playbackOpen && reachedVideoEnd && waitingForSubmission == null
                && submittedFrames == collectedFrames && preparedFrame == null
                && pendingAudio.isEmpty()) {
            finishPlayback();
        }
    }

    private void displayPreparedFrame(long now) {
        PreparedFrame frame = preparedFrame;
        preparedFrame = null;
        currentTimestampUs = frame.logicalTimestampUs();
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        long remainingUs = (frame.targetTimeNs() - frame.preparedAtNs()) / 1_000L;
        framesDisplayed++;
        totalFrameHeadroomUs += remainingUs;
        if (remainingUs < 0) {
            latePreparedFrames++;
        }

        // Keep the starter code's once-per-second trace for direct comparison.
        if (intFrameRate > 0 && frame.frameNumber() % intFrameRate == 0) {
            if (remainingUs < 0) {
                System.out.printf("Frame headroom is \u001B[31m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            } else {
                System.out.printf("Frame headroom is \u001B[32m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            }
        }

        notifyFrameReady(frame.image());
    }

    private void queueAudio(long timestampUs, Frame frame) {
        if (!audioOutputEnabled || audioPlayer == null) {
            return;
        }

        byte[] samples = audioPlayer.copySamples(frame);
        if (samples.length > 0) {
            pendingAudio.add(new PendingAudio(timestampUs, samples));
        }
    }

    private void writeDueAudio(long now) {
        // Handle some obvious early exits
        if (!audioOutputEnabled || audioPlayer == null) {
            pendingAudio.clear();
            return;
        }
        if (pauseRequested || firstTimestampUs == NO_SEEK_REQUEST || playbackStartNs <= 0) {
            return;
        }

        // Here is the real time dependent logic
        long dueTimestampUs = firstTimestampUs + (now + AUDIO_LEAD_NS - playbackStartNs) / 1_000L;
        while (!pendingAudio.isEmpty()) {
            PendingAudio audio = pendingAudio.peek();
            if (audio.timestampUs() > dueTimestampUs) {
                return;
            }

            int written = audioPlayer.write(audio.samples(), audio.offset(), audio.remaining());
            if (written <= 0) {
                return;
            }

            audio.advance(written);
            if (!audio.finished()) {
                return;
            }
            pendingAudio.remove();
        }
    }

    private void finishPlayback() {
        long endTimestampUs = videoDurationUs != NO_SEEK_REQUEST
                ? videoDurationUs
                : currentTimestampUs;

        closePlaybackResources();
        pauseRequested = true;
        currentTimestampUs = endTimestampUs;
        relativeSeekBaseUs = endTimestampUs;
        notifyStatusChanged("Playback finished");
        notifyPlaybackStateChanged();
    }

    private void handlePlaybackError(Exception e) {
        e.printStackTrace();
        closePlaybackResources();
        pauseRequested = false;
        notifyStatusChanged("Error: " + e.getMessage());
        notifyPlaybackStateChanged();
    }

    private void closePlaybackResources() {
        if (framesDisplayed > 0) {
            double latePercent = 100.0 * latePreparedFrames / framesDisplayed;
            long meanHeadroomUs = totalFrameHeadroomUs / framesDisplayed;
            System.out.printf(
                    "Playback performance [%s]: %d displayed, %d late-prepared (%.1f%%), average headroom %dus%n",
                    singleThreaded ? "sequential" : "concurrent", framesDisplayed,
                    latePreparedFrames, latePercent, meanHeadroomUs);
            framesDisplayed = 0;
            latePreparedFrames = 0;
            totalFrameHeadroomUs = 0;
        }
        playbackTimer.stop();
        playbackOpen = false;
        preparedFrame = null;
        pendingAudio.clear();
        resetProcessingPipeline();
        firstTimestampUs = NO_SEEK_REQUEST;
        playbackStartNs = 0;
        pauseStartedNs = 0;
        audioAvailable = false;
        frameProcessorsInitialised = false;

        if (audioPlayer != null) {
            audioPlayer.close();
            audioPlayer = null;
        }

        if (grabber != null) {
            try {
                grabber.stop();
            } catch (Exception e) {
                // The resource is being closed; there is no useful recovery action.
            }
            try {
                grabber.close();
            } catch (Exception e) {
                // The resource is being closed; there is no useful recovery action.
            }
            grabber = null;
        }

        if (converter != null) {
            converter.close();
            converter = null;
        }
    }

    private String mediaName(File file) {
        String fileName = file.getName();
        int extensionStart = fileName.lastIndexOf('.');
        if (extensionStart <= 0) {
            return fileName;
        }
        return fileName.substring(0, extensionStart);
    }

    private long clampSeekTimestamp(long timestampUs) {
        long clampedTimestampUs = Math.max(0, timestampUs);
        long durationUs = videoDurationUs;
        if (durationUs != NO_SEEK_REQUEST) {
            clampedTimestampUs = Math.min(clampedTimestampUs, durationUs);
        }
        return clampedTimestampUs;
    }

    private long displayableSeekTimestamp(long logicalTimestampUs) {
        long durationUs = videoDurationUs;
        if (durationUs == NO_SEEK_REQUEST || logicalTimestampUs < durationUs) {
            return logicalTimestampUs;
        }

        int frameDurationUs = videoFrameDurationUs;
        if (frameDurationUs <= 0) {
            return Math.max(0, durationUs - 1);
        }

        return Math.max(0, durationUs - frameDurationUs);
    }

    private void processFrame(Frame frame, InfoFrame info) throws Exception {
        for (FrameProcessor processor : frameProcessors) {
            processor.process(frame, info);
        }
    }

    private void initialiseFrameProcessors(InfoVideo videoInfo) throws Exception {
        for (FrameProcessor processor : frameProcessors) {
            processor.initialise(videoInfo);
        }
    }

    private Frame grabFrame() throws Exception {
        if (audioAvailable) {
            return grabber.grab();
        }
        return grabber.grabImage();
    }

    private void flushAudioOutput() {
        if (audioPlayer != null) {
            audioPlayer.flush();
        }
    }

    private void notifyVideoSizeChanged(int width, int height) {
        videoSizeChangedHandler.accept(width, height);
    }

    private void notifyFrameReady(Image image) {
        frameReadyHandler.accept(image);
    }

    private void notifyStatusChanged(String status) {
        statusChangedHandler.accept(status);
    }

    private void notifyPlaybackStateChanged() {
        playbackStateChangedHandler.accept(playbackOpen, pauseRequested);
    }

    private void notifyAudioOutputStateChanged() {
        audioOutputStateChangedHandler.accept(audioOutputEnabled);
    }

    private record PreparedFrame(
            Image image,
            int frameNumber,
            long mediaTimestampUs,
            long logicalTimestampUs,
            long targetTimeNs,
            long preparedAtNs) {
    }

    private static class PendingAudio {
        private final long timestampUs;
        private final byte[] samples;
        private int offset;

        PendingAudio(long timestampUs, byte[] samples) {
            this.timestampUs = timestampUs;
            this.samples = samples;
        }

        long timestampUs() {
            return timestampUs;
        }

        byte[] samples() {
            return samples;
        }

        int offset() {
            return offset;
        }

        int remaining() {
            return samples.length - offset;
        }

        void advance(int byteCount) {
            offset += byteCount;
        }

        boolean finished() {
            return offset >= samples.length;
        }
    }
}
