# COMP3011 Assignment 2 - Java Video Player

**Student:** Yosep Monier Nababan (a1939679)

This project extends the supplied JavaFX video player for COMP3011. Part 1 adds command-line options for selecting video effects, while Part 2 uses multiple worker threads to process video frames concurrently.

## Running the project

Requires **JDK 25 or newer** and **Maven**. Run these commands from the folder containing `pom.xml`:

```bash
mvn validate
mvn compile
mvn package
mvn verify
```

To see the available options:

```bash
mvn javafx:run "-Djavafx.args=--help"
```

To play a video with audio and effects (replace the file path with your own):

```bash
mvn javafx:run "-Djavafx.args=-a -sfdn D:/sample.mp4"
```

The player supports 11 frame effects, including scratches (`-s`), flickering (`-f`), dust (`-d`), and frame numbers (`-n`). Short options can be combined, and effects are applied in command-line order, including repeated options. Run `--help` for the full list and the original playback options.

## Concurrent processing

`VideoPlayerModel` handles playback timing, decoding, audio scheduling, and interaction with JavaFX. `FrameProcessingPool` sends video frames to a fixed pool of background workers for effect processing.

Each submitted frame is copied so workers do not share the decoder's pixel buffer. Each worker also has its own effect instances. Completed frames are collected in sequence order, and a bounded number of in-flight frames prevents excessive buffering. The processing pool is reset when seeking or stopping so old results are discarded.

The original single-threaded effect-processing mode is retained for comparison. Set the environment variable `VIDEO_SINGLE_THREADED=true` before launching the player to use it. With the variable unset, selected effects use the concurrent processing path.

## Testing

JUnit tests are in `src/test/java/` and can be run with:

```bash
mvn verify
```

They cover command-line parsing, worker-pool behaviour, frame ordering, and a sequential-versus-parallel processing comparison. The performance test checks that processed frame outputs match and reports timing; it does not require a fixed speedup. Playback controls and responsiveness should also be checked with a real video.

## Attribution

Based on the COMP3011 starter code by Simon Ratcliffe. Modifications and tests by Yosep Monier Nababan (a1939679), with assistance from OpenAI ChatGPT. Changes are also identified in relevant source-file headers. COMP3011 Assignment 2 - Java Local Project

Repo link: https://github.com/Yosep97581/COMP3011_Assignment2

