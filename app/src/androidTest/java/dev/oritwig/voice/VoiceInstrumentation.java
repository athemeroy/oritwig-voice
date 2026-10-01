package dev.oritwig.voice;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.oritwig.audio.RnnoiseDenoiser;

/** Real on-device JNI, local workflow and native UI checks; synthetic audio only. */
public final class VoiceInstrumentation extends Instrumentation {
  int passed;
  StringBuilder log = new StringBuilder();

  void check(String label, boolean ok) {
    if (!ok) throw new AssertionError(label);
    passed++;
    log.append("PASS ").append(label).append('\n');
  }

  File fixture(Context c, String name, int samples) throws Exception {
    File f = new File(c.getCacheDir(), name);
    Random random = new Random(712);
    try (OutputStream out = new BufferedOutputStream(new FileOutputStream(f))) {
      WavIO.header(out, samples);
      for (int i = 0; i < samples; i++) {
        int v =
            (int) (5000 * Math.sin(i * 2 * Math.PI * 220 / 48000) + random.nextInt(2401) - 1200);
        WavIO.write16(out, v);
      }
    }
    return f;
  }

  byte[] hash(File f) throws Exception {
    return MessageDigest.getInstance("SHA-256")
        .digest(java.nio.file.Files.readAllBytes(f.toPath()));
  }

  void idle(VoiceController c) {
    long end = SystemClock.elapsedRealtime() + 180000;
    while (c.busy() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(30);
    if (c.busy()) throw new AssertionError("job timeout");
    waitForIdleSync();
  }

  void awaitPlayback(MainActivity a) {
    boolean[] started = {false};
    long deadline = SystemClock.elapsedRealtime() + 15000;
    while (!started[0] && SystemClock.elapsedRealtime() < deadline) {
      runOnMainSync(
          () -> {
            android.widget.Button button = a.getWindow().getDecorView().findViewWithTag("play");
            started[0] = button != null && button.getText().toString().contains("Pause");
          });
      if (!started[0]) SystemClock.sleep(100);
    }
    check("MediaPlayer prepared and started", started[0]);
  }

  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    start();
  }

  @Override
  public void onStart() {
    Bundle result = new Bundle();
    MainActivity activity = null;
    try {
      Context context = getTargetContext();
      for (int length : new int[] {1, 479, 480, 481, 1201, 144000}) {
        File input = fixture(context, "test-input.wav", length),
            output = new File(context.getCacheDir(), "test-cleaned.wav");
        output.delete();
        byte[] originalHash = hash(input);
        SpeechProcessor.process(input, output, () -> false, p -> {});
        check("Exact output sample count " + length, WavIO.inspect(output).samples == length);
        check("Original preserved " + length, Arrays.equals(hash(input), originalHash));
        // Independent use of the actual JNI API validates the shell's declared pad/flush/trim
        // policy.
        try (RandomAccessFile source = new RandomAccessFile(input, "r");
            RandomAccessFile actual = new RandomAccessFile(output, "r");
            RnnoiseDenoiser engine = new RnnoiseDenoiser()) {
          source.seek(44);
          actual.seek(44);
          long read = 0, written = 0;
          short[] frame = new short[480];
          int frames = (length + 479) / 480;
          for (int n = 0; n <= frames; n++) {
            Arrays.fill(frame, (short) 0);
            for (int i = 0; i < 480 && read < length; i++, read++)
              frame[i] = Short.reverseBytes(source.readShort());
            engine.processFrame(frame);
            if (n > 0)
              for (int i = 0; i < 480 && written < length; i++, written++)
                if (frame[i] != Short.reverseBytes(actual.readShort()))
                  throw new AssertionError("tail alignment mismatch");
          }
        }
        check("JNI pad/flush/trim reference " + length, true);
        if (length == 144000)
          check(
              "Real engine changes synthetic noisy signal",
              !Arrays.equals(hash(input), hash(output)));
        output.delete();
        input.delete();
      }
      File input = fixture(context, "cancel-input.wav", 48000),
          output = new File(context.getCacheDir(), "cancel-output.wav");
      output.delete();
      try {
        SpeechProcessor.process(input, output, () -> true, p -> {});
        throw new AssertionError("cancel failed");
      } catch (CancellationException expected) {
        check("Cancelled cleanup removes partial output", !output.exists());
      }
      RecordingStore store = new RecordingStore(context);
      File imported = fixture(context, "import-source.wav", 144001);
      byte[] original = hash(imported);
      RecordingStore.Entry entry = store.publish(imported, "  Synthetic engine check  ");
      check("Published name trimmed", entry.name.equals("Synthetic engine check"));
      check("Published original byte-identical", Arrays.equals(original, hash(entry.original())));
      store.rename(entry, "Renamed synthetic engine check");
      check(
          "Changed name persisted",
          store.find(entry.id).name.equals("Renamed synthetic engine check"));
      store.rename(entry, "Synthetic engine check");
      activity =
          (MainActivity)
              startActivitySync(
                  new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      waitForIdleSync();
      final MainActivity a = activity;
      runOnMainSync(
          () -> {
            a.controller.select(entry.id);
          });
      waitForIdleSync();
      runOnMainSync(
          () -> {
            View clean = a.getWindow().getDecorView().findViewWithTag("clean");
            check("Unprocessed detail offers cleanup", clean != null);
            clean.performClick();
          });
      idle(a.controller);
      check("UI cleanup produces WAV", store.find(entry.id).isClean());
      check("Cleanup controller succeeds", a.controller.error.isEmpty());
      check("Original survives UI cleanup", Arrays.equals(original, hash(entry.original())));
      runOnMainSync(
          () -> {
            check(
                "Compare original control",
                a.getWindow().getDecorView().findViewWithTag("original") != null);
            check(
                "Compare cleaned control",
                a.getWindow().getDecorView().findViewWithTag("cleaned").isEnabled());
            a.getWindow().getDecorView().findViewWithTag("original").performClick();
            a.getWindow().getDecorView().findViewWithTag("play").performClick();
          });
      awaitPlayback(a);
      waitForIdleSync();
      check("MediaPlayer original playback no error", a.controller.error.isEmpty());
      runOnMainSync(
          () -> {
            a.getWindow().getDecorView().findViewWithTag("cleaned").performClick();
            a.getWindow().getDecorView().findViewWithTag("play").performClick();
          });
      awaitPlayback(a);
      waitForIdleSync();
      check("MediaPlayer cleaned playback no error", a.controller.error.isEmpty());
      File exported = new File(context.getCacheDir(), "exported.wav");
      runOnMainSync(() -> a.controller.export(entry, true, Uri.fromFile(exported)));
      idle(a.controller);
      check("Export byte-identical", Arrays.equals(hash(exported), hash(entry.cleaned())));
      File bad = new File(context.getCacheDir(), "bad.wav");
      try (OutputStream out = new FileOutputStream(bad)) {
        out.write(new byte[44]);
      }
      int before = store.list().size();
      runOnMainSync(() -> a.controller.importWav(Uri.fromFile(bad), "bad"));
      idle(a.controller);
      check("Malformed import error visible", !a.controller.error.isEmpty());
      check("Malformed import not published", store.list().size() == before);
      runOnMainSync(
          () -> {
            a.controller.clearMessages();
            a.controller.selectedId = null;
            a.changed();
          });
      waitForIdleSync();
      runOnMainSync(
          () -> {
            a.getWindow().getDecorView().findViewWithTag("settings").performClick();
            check(
                "About accessible", a.getWindow().getDecorView().findViewWithTag("about") != null);
            a.getWindow().getDecorView().findViewWithTag("about").performClick();
            check(
                "Licenses accessible",
                a.getWindow().getDecorView().findViewWithTag("licenses") != null);
            a.onBackPressed();
            a.onBackPressed();
          });
      waitForIdleSync();
      File cancelFixture = fixture(context, "controller-cancel.wav", 144000);
      RecordingStore.Entry cancelEntry =
          store.publish(cancelFixture, "Disposable cancellation test");
      runOnMainSync(
          () -> {
            a.controller.clean(cancelEntry);
            a.controller.cancel();
          });
      idle(a.controller);
      check("Controller cancellation keeps original", cancelEntry.original().isFile());
      check(
          "Controller cancellation removes pending cleaned output",
          !cancelEntry.cleaned().exists()
              && !new File(cancelEntry.directory, "cleaned.pending.wav").exists());
      store.delete(cancelEntry);
      check("Delete removes chosen local entry", store.find(cancelEntry.id) == null);
      runOnMainSync(
          () -> {
            a.controller.clearMessages();
            a.controller.selectedId = entry.id;
            a.changed();
          });
      waitForIdleSync();
      ActivityMonitor recreation = addMonitor(MainActivity.class.getName(), null, false);
      runOnMainSync(a::recreate);
      MainActivity rebuilt = (MainActivity) waitForMonitorWithTimeout(recreation, 15000);
      check(
          "Activity recreation retains running controller",
          rebuilt != null && rebuilt.controller == a.controller);
      check(
          "Activity recreation restores selected recording",
          entry.id.equals(rebuilt.controller.selectedId));
      removeMonitor(recreation);
      waitForIdleSync();
      check(
          "No INTERNET permission",
          context
                  .getPackageManager()
                  .checkPermission("android.permission.INTERNET", context.getPackageName())
              == android.content.pm.PackageManager.PERMISSION_DENIED);
      result.putString(
          "stream",
          log
              + "\nPASS "
              + passed
              + " checks; synthetic processing only, no physical microphone/listening-quality"
              + " claim.\n");
      finish(-1, result);
    } catch (Throwable ex) {
      result.putString("stream", log + "\nFAIL " + android.util.Log.getStackTraceString(ex));
      finish(1, result);
    }
  }
}
