package dev.oritwig.voice;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** RIFF container plumbing only. No codec, resampling, mixing, filtering or denoising. */
public final class WavIO {
  public static final int RATE = 48000;
  public static final long MAX_SAMPLES = RATE * 300L;
  public static final long MAX_IMPORT_BYTES = 32L * 1024 * 1024;
  public static final String SUPPORTED = "48 kHz · mono · 16-bit PCM WAV · up to 5 minutes";

  public static final class Info {
    public final long offset, samples;

    Info(long offset, long samples) {
      this.offset = offset;
      this.samples = samples;
    }

    public long millis() {
      return samples * 1000 / RATE;
    }
  }

  private WavIO() {}

  public static Info inspect(File file) throws IOException {
    try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
      long length = in.length();
      if (length < 44 || length > MAX_IMPORT_BYTES)
        throw invalid("File is empty, too small, or over 32 MB.");
      if (!four(in).equals("RIFF")) throw invalid("Choose an uncompressed WAV file.");
      long riff = u32(in);
      if (!four(in).equals("WAVE") || riff + 8 != length)
        throw invalid("WAV container is incomplete or malformed.");
      boolean fmt = false;
      long dataOffset = -1, dataSize = -1;
      while (in.getFilePointer() < length) {
        if (length - in.getFilePointer() < 8) throw invalid("Truncated WAV chunk.");
        String tag = four(in);
        long size = u32(in), start = in.getFilePointer(), end = start + size;
        if (end > length || end + (size & 1) > length) throw invalid("Truncated WAV audio.");
        if (tag.equals("fmt ")) {
          if (fmt || size < 16) throw invalid("Unsupported WAV format header.");
          int format = u16(in), channels = u16(in);
          long rate = u32(in), byteRate = u32(in);
          int block = u16(in), bits = u16(in);
          if (format != 1
              || channels != 1
              || rate != RATE
              || byteRate != RATE * 2L
              || block != 2
              || bits != 16)
            throw invalid(
                "This version accepts only 48 kHz mono 16-bit PCM WAV. Stereo, compressed audio and"
                    + " other rates are not converted.");
          fmt = true;
        } else if (tag.equals("data")) {
          if (dataOffset != -1) throw invalid("Multiple WAV audio chunks are unsupported.");
          dataOffset = start;
          dataSize = size;
        }
        in.seek(end + (size & 1));
      }
      if (!fmt || dataOffset < 0 || dataSize <= 0 || dataSize % 2 != 0)
        throw invalid("WAV has no complete PCM audio.");
      if (dataSize / 2 > MAX_SAMPLES) throw invalid("Choose speech up to 5 minutes long.");
      return new Info(dataOffset, dataSize / 2);
    }
  }

  public static void copy(InputStream in, OutputStream out, long limit, BooleanSupplier cancelled)
      throws IOException {
    byte[] buffer = new byte[32768];
    long total = 0;
    int n;
    while ((n = in.read(buffer)) != -1) {
      if (cancelled.getAsBoolean()) throw new CancellationException();
      total += n;
      if (total > limit) throw invalid("File is over the 32 MB import limit.");
      out.write(buffer, 0, n);
    }
  }

  public static void header(OutputStream out, long samples) throws IOException {
    if (samples < 0 || samples > MAX_SAMPLES) throw invalid("Unsupported audio length.");
    out.write("RIFF".getBytes(StandardCharsets.US_ASCII));
    write32(out, 36 + samples * 2);
    out.write("WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
    write32(out, 16);
    write16(out, 1);
    write16(out, 1);
    write32(out, RATE);
    write32(out, RATE * 2);
    write16(out, 2);
    write16(out, 16);
    out.write("data".getBytes(StandardCharsets.US_ASCII));
    write32(out, samples * 2);
  }

  public static void write16(OutputStream out, int n) throws IOException {
    out.write(n & 255);
    out.write((n >>> 8) & 255);
  }

  private static void write32(OutputStream out, long n) throws IOException {
    for (int i = 0; i < 4; i++) out.write((int) (n >>> (i * 8)) & 255);
  }

  private static String four(RandomAccessFile f) throws IOException {
    byte[] b = new byte[4];
    f.readFully(b);
    return new String(b, StandardCharsets.US_ASCII);
  }

  private static int u16(RandomAccessFile f) throws IOException {
    return f.readUnsignedByte() | f.readUnsignedByte() << 8;
  }

  private static long u32(RandomAccessFile f) throws IOException {
    return Integer.toUnsignedLong(Integer.reverseBytes(f.readInt()));
  }

  private static IOException invalid(String message) {
    return new IOException(message);
  }
}
