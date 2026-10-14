/*
 * Assignment 2 regression tests by Yosep Monier Nababan (a1939679),
 * with assistance from OpenAI ChatGPT.
 */
package comp3011;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandLineControllerTest {
    @TempDir Path tempDir;

    private File sampleVideo() throws IOException {
        return java.nio.file.Files.createFile(tempDir.resolve("sample.mp4")).toFile();
    }

    @Test
    void acceptsStackedShortOptionsInOrder() throws IOException {
        File file = sampleVideo();
        CommandLineController cli = new CommandLineController(
                new String[] { "-jfdn", file.getPath() });

        assertTrue(cli.shouldLaunchApplication());
        assertEquals(List.of(FrameEffect.JITTER, FrameEffect.FLICKER,
                FrameEffect.DUST, FrameEffect.NUMBER), cli.getEffects());
    }

    @Test
    void mixesLongAndShortOptionsAndKeepsRepetitions() throws IOException {
        File file = sampleVideo();
        CommandLineController cli = new CommandLineController(new String[] {
                "-jf", "--dust-frames", "-d", "--number-frames", file.getPath()
        });
        assertEquals(List.of(FrameEffect.JITTER, FrameEffect.FLICKER,
                FrameEffect.DUST, FrameEffect.DUST, FrameEffect.NUMBER), cli.getEffects());
    }

    @Test
    void longFlagsBehaveLikeEquivalentShortFlags() throws IOException {
        File file = sampleVideo();
        CommandLineController longFlags = new CommandLineController(new String[] {
                "--jitter-frames", "--flicker-frames", "--dust-frames",
                "--dust-frames", "--number-frames", file.getPath()
        });
        CommandLineController shortFlags = new CommandLineController(new String[] {
                "-j", "-f", "-d", "-d", "-n", file.getPath()
        });
        assertEquals(longFlags.getEffects(), shortFlags.getEffects());
    }

    @Test
    void supportsAllElevenEffects() throws IOException {
        File file = sampleVideo();
        CommandLineController cli = new CommandLineController(new String[] {
                "-nsfwyvdjmbp", file.getPath()
        });
        assertEquals(11, cli.getEffects().size());
        assertEquals(List.of(FrameEffect.values()), cli.getEffects());
    }

    @Test
    void preservesExistingPlaybackOptions() throws IOException {
        File file = sampleVideo();
        CommandLineController cli = new CommandLineController(new String[] {
                "-ax1", "-sn", file.getPath()
        });
        assertTrue(cli.shouldLaunchApplication());
        assertTrue(cli.isAudioRequested());
        assertTrue(cli.isMaximiseRequested());
        assertEquals(Integer.valueOf(1), cli.getDisplayId());
        assertEquals(List.of(FrameEffect.SCRATCH, FrameEffect.NUMBER), cli.getEffects());
    }

    @Test
    void rejectsUnknownFlag() throws IOException {
        File file = sampleVideo();
        CommandLineController cli = new CommandLineController(new String[] {
                "-jz", file.getPath()
        });
        assertFalse(cli.shouldLaunchApplication());
        assertEquals("Unknown option: -z", cli.getErrorMessage());
    }

    @Test
    void rejectsConflictingMonitorSelections() throws IOException {
        File file = sampleVideo();
        CommandLineController cli = new CommandLineController(new String[] {
                "-12", file.getPath()
        });
        assertEquals("Only one display option can be used", cli.getErrorMessage());
    }

    @Test
    void doesNotOpenPlayerWhenOnlyHelpRequested() {
        CommandLineController cli = new CommandLineController(new String[] { "--help" });
        assertFalse(cli.shouldLaunchApplication());
        assertEquals(0, cli.getExitCode());
    }
}
