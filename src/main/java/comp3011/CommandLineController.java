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
 * Changes: recognise long and stacked short options, preserving the requested
 * order and repetitions of video effects separately from video playback.
 */
package comp3011;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
/**
 * Created in the VideoPlayerApp's main function to receive and parse the video player's command-line arguments.
 *
 * <p>
 * Extracts the video file and supported launch options, reports invalid input
 * or help text, and exposes the resulting launch configuration via methods to the
 * {@link VideoPlayerApp}.
 * </p>
 * Reads the command-line arguments and creates a configuration for the player.
 * This class handles option syntax only; it does not process video frames.
 */
public class CommandLineController {
    private final String[] args;
    private final List<FrameEffect> effects = new ArrayList<>();

    private boolean helpRequested;
    private boolean audioRequested;
    private boolean maximiseRequested;
    private Integer displayId;
    private File videoFile;
    private String errorMessage;

    public CommandLineController(String[] args) {
        this.args = args.clone();
        parse();
        if (errorMessage != null) {
            System.out.println(errorMessage);
        }
        if (helpRequested) {
            printHelp();
        }
    }

    public File getVideoFile() {
        return videoFile;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public int getExitCode() {
        return errorMessage == null ? 0 : 1;
    }

    public Integer getDisplayId() {
        return displayId;
    }

    public boolean isAudioRequested() {
        return audioRequested;
    }

    public boolean isMaximiseRequested() {
        return maximiseRequested;
    }

    /** Returns the requested effects in order, including repeated options. */
    public List<FrameEffect> getEffects() {
        return List.copyOf(effects);
    }

    public boolean shouldLaunchApplication() {
        return errorMessage == null && !helpRequested && videoFile != null;
    }

    private void parse() {
        List<String> videoFiles = new ArrayList<>();
        boolean optionsEnded = false;

        for (String arg : args) {
            if (!optionsEnded && "--".equals(arg)) {
                optionsEnded = true;
            } else if (!optionsEnded && arg.startsWith("--")) {
                parseLongOption(arg);
            } else if (!optionsEnded && arg.startsWith("-") && arg.length() > 1) {
                parseShortOptions(arg);
            } else {
                videoFiles.add(arg);
            }

            if (errorMessage != null) {
                return;
            }
        }

        if (videoFiles.size() > 1) {
            errorMessage = "Usage: VideoPlayer [options] [video-file]";
        } else if (videoFiles.size() == 1) {
            videoFile = new File(videoFiles.get(0));
            if (!videoFile.isFile() && !helpRequested) {
                errorMessage = "File not found: " + videoFile.getPath();
                videoFile = null;
            }
        } else if (!helpRequested) {
            errorMessage = "No video file specified.";
        }
    }

    private void parseLongOption(String arg) {
        switch (arg) {
            case "--help" -> helpRequested = true;
            case "--audio" -> audioRequested = true;
            case "--maximise" -> maximiseRequested = true;
            case "--monitor-1" -> setDisplayId(1);
            case "--monitor-2" -> setDisplayId(2);
            default -> {
                FrameEffect effect = FrameEffect.fromLongOption(arg.substring(2));
                if (effect == null) {
                    errorMessage = "Unknown option: " + arg;
                } else {
                    effects.add(effect);
                }
            }
        }
    }

    private void parseShortOptions(String arg) {
        // Each letter represents one independent option. For example, -sfs
        // means scratches, flicker, then another set of scratches.
        for (int i = 1; i < arg.length(); i++) {
            char option = arg.charAt(i);
            switch (option) {
                case 'h' -> helpRequested = true;
                case 'a' -> audioRequested = true;
                case 'x' -> maximiseRequested = true;
                case '1' -> setDisplayId(1);
                case '2' -> setDisplayId(2);
                default -> {
                    FrameEffect effect = FrameEffect.fromShortOption(option);
                    if (effect == null) {
                        errorMessage = "Unknown option: -" + option;
                    } else {
                        effects.add(effect);
                    }
                }
            }
            if (errorMessage != null) {
                return;
            }
        }
    }

    private void setDisplayId(int displayId) {
        if (this.displayId != null && this.displayId != displayId) {
            errorMessage = "Only one display option can be used";
            return;
        }
        this.displayId = displayId;
    }

    private void printHelp() {
        System.out.println("Usage: VideoPlayer [options] [video-file]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -h, --help         Show this help message");
        System.out.println("  -a, --audio        Play audio");
        System.out.println("  -x, --maximise     Open the player maximised");
        System.out.println("  -1, --monitor-1    Open the player on display 1");
        System.out.println("  -2, --monitor-2    Open the player on display 2");
        System.out.println();
        System.out.println("Frame processors:");
        for (FrameEffect effect : FrameEffect.values()) {
            System.out.println(effect.helpLine());
        }
        System.out.println();
        System.out.println("Frame processors are applied in command-line order and may be repeated.");
        System.out.println("Example: -nssnfwyvdjmbp numbers, scratches twice, numbers again, flickers,");
        System.out.println("converts, warms, vignettes, dusts, jitters, mottles, bleeds, then peppers.");
    }
}
