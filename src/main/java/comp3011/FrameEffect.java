/*
 * Based on starter code supplied for Adelaide University COMP3011 Assignment 2.
 *
 * New file by Yosep Monier Nababan (a1939679), with assistance from OpenAI ChatGPT.
 * Maps command-line effect options to the supplied frame processor classes.
 */
package comp3011;

import java.util.function.Supplier;

/**
 * Describes the visual effects supported by the command-line interface.
 * We store effect types rather than shared objects so that each use of an
 * option can create its own processor. This will also help when introducing
 * independent worker threads in Part 2.
 */
public enum FrameEffect {
    NUMBER('n', "number-frames", "Render the frame number onto each frame", FrameNumberer::new),
    SCRATCH('s', "scratch-frames", "Render vertical film scratches", FrameScratcher::new),
    FLICKER('f', "flicker-frames", "Randomly dim frames", FrameFlickerer::new),
    BLACK_AND_WHITE('w', "black-and-white", "Convert frames to black and white", FrameBlackAndWhiter::new),
    YELLOW('y', "yellow-frames", "Apply a warmer colour temperature", FrameYellower::new),
    VIGNETTE('v', "vignette-frames", "Darken the frame edges", FrameVignetter::new),
    DUST('d', "dust-frames", "Render dust and hair marks", FrameDuster::new),
    JITTER('j', "jitter-frames", "Randomly displace frames by a few pixels", FrameJitterer::new),
    MOTTLE('m', "mottle-frames", "Add cloudy emulsion mottling", FrameMottler::new),
    BLEED('b', "bleed-frames", "Bleed light into frames", FrameBleeder::new),
    PEPPER('p', "pepper-frames", "Pepper frames with dark spots/blotches", FramePepperer::new);

    private final char shortOption;
    private final String longOption;
    private final String description;
    private final Supplier<FrameProcessor> factory;

    FrameEffect(char shortOption, String longOption, String description,
            Supplier<FrameProcessor> factory) {
        this.shortOption = shortOption;
        this.longOption = longOption;
        this.description = description;
        this.factory = factory;
    }

    public FrameProcessor createProcessor() {
        return factory.get();
    }

    public static FrameEffect fromShortOption(char option) {
        for (FrameEffect effect : values()) {
            if (effect.shortOption == option) {
                return effect;
            }
        }
        return null;
    }

    public static FrameEffect fromLongOption(String option) {
        for (FrameEffect effect : values()) {
            if (effect.longOption.equals(option)) {
                return effect;
            }
        }
        return null;
    }

    public String helpLine() {
        return String.format("  -%c, --%-16s %s", shortOption, longOption, description);
    }
}
