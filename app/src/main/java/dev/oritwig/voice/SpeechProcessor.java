package dev.oritwig.voice;

import java.io.*;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import org.oritwig.audio.RnnoiseDenoiser;

/** Streaming/tail adapter around the unchanged RNNoise engine. */
public final class SpeechProcessor {
  private SpeechProcessor() {}

  public static void process(File input, File output, BooleanSupplier cancel, IntConsumer progress)
      throws IOException {
    WavIO.Info info = WavIO.inspect(input);
    if (output.exists()) throw new IOException("Output already exists.");
    boolean success = false;
    try {
      try (FileInputStream source = new FileInputStream(input);
          DataInputStream in = new DataInputStream(new BufferedInputStream(source));
          OutputStream out = new BufferedOutputStream(new FileOutputStream(output));
          RnnoiseDenoiser engine = new RnnoiseDenoiser()) {
        source.getChannel().position(info.offset);
        WavIO.header(out, info.samples);
        long inputFrames = (info.samples + 479) / 480;
        long read = 0, written = 0;
        short[] frame = new short[480];
        for (long f = 0; f <= inputFrames; f++) {
          if (cancel.getAsBoolean()) throw new CancellationException();
          Arrays.fill(frame, (short) 0);
          for (int i = 0; i < 480 && read < info.samples; i++, read++)
            frame[i] = Short.reverseBytes(in.readShort());
          engine.processFrame(frame);
          // Original RNNoise has one 480-sample analysis/synthesis overlap delay.
          // Skip its first frame; one zero frame flushes it; retain exact input length.
          if (f > 0)
            for (int i = 0; i < 480 && written < info.samples; i++, written++)
              WavIO.write16(out, frame[i]);
          if (f % 25 == 0 || f == inputFrames) progress.accept((int) (100 * f / inputFrames));
        }
        if (written != info.samples) throw new IOException("Incomplete RNNoise output.");
      }
      success = true;
    } finally {
      if (!success) output.delete();
    }
  }
}
