package dev.oritwig.voice;

import static org.junit.Assert.*;

import java.io.*;
import java.util.concurrent.CancellationException;
import org.junit.Test;

public class WavIOTest {
  private File wav(int samples) throws Exception {
    File f = File.createTempFile("voice-test-", ".wav");
    f.deleteOnExit();
    try (OutputStream out = new FileOutputStream(f)) {
      WavIO.header(out, samples);
      for (int i = 0; i < samples; i++) WavIO.write16(out, i - 700);
    }
    return f;
  }

  private void reject(File f, String label) throws Exception {
    try {
      WavIO.inspect(f);
      fail(label);
    } catch (IOException expected) {
      assertFalse(expected.getMessage().isEmpty());
    }
  }

  @Test
  public void headerAndTailContract() throws Exception {
    for (int n : new int[] {1, 479, 480, 481, 1201, 48000}) {
      File f = wav(n);
      WavIO.Info i = WavIO.inspect(f);
      assertEquals(n, i.samples);
      assertEquals(44, i.offset);
      assertEquals(44 + n * 2, f.length());
    }
  }

  @Test
  public void rejectsStereoAndWrongRateAndCompressed() throws Exception {
    for (int offset : new int[] {20, 22, 24, 28, 32, 34}) {
      File f = wav(480);
      try (RandomAccessFile r = new RandomAccessFile(f, "rw")) {
        r.seek(offset);
        r.write(3);
      }
      reject(f, "bad format at " + offset);
    }
  }

  @Test
  public void rejectsEmptyAudio() throws Exception {
    reject(wav(0), "empty");
  }

  @Test
  public void rejectsTruncatedAudio() throws Exception {
    File f = wav(480);
    try (RandomAccessFile r = new RandomAccessFile(f, "rw")) {
      r.setLength(r.length() - 1);
    }
    reject(f, "truncated");
  }

  @Test
  public void rejectsWrongContainer() throws Exception {
    File f = wav(480);
    try (RandomAccessFile r = new RandomAccessFile(f, "rw")) {
      r.writeBytes("RF64");
    }
    reject(f, "RF64");
  }

  @Test
  public void rejectsOversizeMetadataChunk() throws Exception {
    File f = wav(480);
    try (RandomAccessFile r = new RandomAccessFile(f, "rw")) {
      r.seek(40);
      r.writeInt(-1);
    }
    reject(f, "oversize");
  }

  @Test
  public void acceptsUnknownPaddedChunks() throws Exception {
    File basic = wav(481);
    byte[] raw = java.nio.file.Files.readAllBytes(basic.toPath());
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(raw, 0, 12);
    out.write(new byte[] {'J', 'U', 'N', 'K', 3, 0, 0, 0, 1, 2, 3, 0});
    out.write(raw, 12, raw.length - 12);
    byte[] bytes = out.toByteArray();
    int len = bytes.length - 8;
    for (int i = 0; i < 4; i++) bytes[4 + i] = (byte) (len >>> (8 * i));
    File f = File.createTempFile("voice-chunk-", ".wav");
    f.deleteOnExit();
    java.nio.file.Files.write(f.toPath(), bytes);
    assertEquals(481, WavIO.inspect(f).samples);
    assertEquals(56, WavIO.inspect(f).offset);
  }

  @Test
  public void copyHasBoundAndCancellation() throws Exception {
    try {
      WavIO.copy(
          new ByteArrayInputStream(new byte[101]), new ByteArrayOutputStream(), 100, () -> false);
      fail();
    } catch (IOException expected) {
    }
    try {
      WavIO.copy(
          new ByteArrayInputStream(new byte[101]), new ByteArrayOutputStream(), 200, () -> true);
      fail();
    } catch (CancellationException expected) {
    }
  }

  @Test
  public void maximumIsFiveMinutes() throws Exception {
    try {
      WavIO.header(new ByteArrayOutputStream(), WavIO.MAX_SAMPLES + 1);
      fail();
    } catch (IOException expected) {
    }
  }
}
