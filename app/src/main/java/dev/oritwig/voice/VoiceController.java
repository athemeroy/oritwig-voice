package dev.oritwig.voice;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.*;

/** Foreground-only capture and serial file jobs; retained through Activity recreation. */
final class VoiceController {
  interface Listener {
    void changed();
  }

  enum State {
    IDLE,
    RECORDING,
    SAVING,
    IMPORTING,
    PROCESSING,
    EXPORTING
  }

  final Context context;
  final RecordingStore store;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  volatile Listener listener;
  volatile State state = State.IDLE;
  volatile boolean cancel, stopCapture;
  volatile int progress;
  volatile long capturedSamples;
  volatile String selectedId, notice = "", error = "";
  private boolean closed;

  VoiceController(Context c) {
    context = c.getApplicationContext();
    store = new RecordingStore(context);
    cleanupTemporary();
  }

  private void cleanupTemporary() {
    File[] files = context.getCacheDir().listFiles();
    if (files != null) for (File f : files) if (f.getName().startsWith("voice-")) f.delete();
  }

  void attach(Listener l) {
    listener = l;
  }

  void changed() {
    main.post(
        () -> {
          Listener l = listener;
          if (l != null) l.changed();
        });
  }

  boolean busy() {
    return state != State.IDLE;
  }

  synchronized boolean begin(State s) {
    if (busy() || closed) return false;
    state = s;
    cancel = false;
    progress = 0;
    error = "";
    notice = "";
    changed();
    return true;
  }

  void finish() {
    state = State.IDLE;
    changed();
  }

  void cancel() {
    cancel = true;
    if (state == State.RECORDING) stopCapture = true;
    changed();
  }

  void stopRecording() {
    if (state == State.RECORDING) {
      stopCapture = true;
      state = State.SAVING;
      changed();
    }
  }

  void clearMessages() {
    notice = "";
    error = "";
  }

  void select(String id) {
    selectedId = id;
    clearMessages();
    changed();
  }

  private File temporary(String suffix) throws IOException {
    return File.createTempFile("voice-", suffix, context.getCacheDir());
  }

  private String datedName() {
    return "Speech · "
        + new SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(new Date());
  }

  private void failure(Throwable ex) {
    error = (ex.getMessage() == null ? "Audio operation could not finish." : ex.getMessage());
  }

  void importWav(Uri uri, String name) {
    if (!begin(State.IMPORTING)) return;
    worker.execute(
        () -> {
          File tmp = null;
          try {
            tmp = temporary(".wav");
            try (InputStream in = context.getContentResolver().openInputStream(uri);
                OutputStream out = new BufferedOutputStream(new FileOutputStream(tmp))) {
              if (in == null) throw new IOException("The selected file cannot be opened.");
              WavIO.copy(in, out, WavIO.MAX_IMPORT_BYTES, () -> cancel);
            }
            if (cancel) throw new CancellationException();
            WavIO.inspect(tmp);
            RecordingStore.Entry e = store.publish(tmp, name);
            selectedId = e.id;
            notice = "Original imported and preserved. Ready to clean.";
          } catch (CancellationException ex) {
            notice = "Import cancelled. Nothing was added.";
          } catch (Exception ex) {
            failure(ex);
          } finally {
            if (tmp != null) tmp.delete();
            finish();
          }
        });
  }

  void clean(RecordingStore.Entry entry) {
    if (!begin(State.PROCESSING)) return;
    worker.execute(
        () -> {
          File tmp = new File(entry.directory, "cleaned.pending.wav");
          tmp.delete();
          try {
            SpeechProcessor.process(
                entry.original(),
                tmp,
                () -> cancel,
                p -> {
                  progress = p;
                  changed();
                });
            if (cancel) throw new CancellationException();
            WavIO.inspect(tmp);
            if (!tmp.renameTo(entry.cleaned())) throw new IOException("Cannot save cleaned audio.");
            notice = "Cleaned version is ready. Compare it with your original.";
          } catch (CancellationException ex) {
            notice = "Cleanup cancelled. Your original is safe.";
          } catch (Exception | LinkageError ex) {
            failure(ex);
          } finally {
            tmp.delete();
            finish();
          }
        });
  }

  void export(RecordingStore.Entry entry, boolean cleaned, Uri uri) {
    if (!begin(State.EXPORTING)) return;
    worker.execute(
        () -> {
          boolean success = false;
          try {
            File source = cleaned ? entry.cleaned() : entry.original();
            try (InputStream in = new FileInputStream(source);
                OutputStream out = context.getContentResolver().openOutputStream(uri, "wt")) {
              if (out == null) throw new IOException("The destination cannot be opened.");
              WavIO.copy(in, out, WavIO.MAX_IMPORT_BYTES, () -> cancel);
              out.flush();
            }
            if (cancel) throw new CancellationException();
            success = true;
            notice = (cleaned ? "Cleaned" : "Original") + " WAV exported.";
          } catch (CancellationException ex) {
            notice = "Export cancelled.";
          } catch (Exception ex) {
            failure(ex);
          } finally {
            if (!success)
              try {
                if (!DocumentsContract.deleteDocument(context.getContentResolver(), uri))
                  error += " A partial file may remain at the destination.";
              } catch (Exception ignored) {
                error += " A partial file may remain at the destination.";
              }
            finish();
          }
        });
  }

  @SuppressLint("MissingPermission") // MainActivity checks grant immediately before calling.
  void record() {
    if (!begin(State.RECORDING)) return;
    stopCapture = false;
    capturedSamples = 0;
    worker.execute(
        () -> {
          AudioRecord recorder = null;
          File pcm = null, wav = null;
          try {
            int min =
                AudioRecord.getMinBufferSize(
                    WavIO.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0)
              throw new IOException(
                  "This device cannot record 48 kHz mono audio. You can still import a supported"
                      + " WAV.");
            recorder =
                new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    WavIO.RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(min * 2, 9600));
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED)
              throw new IOException(
                  "Microphone could not start. Try closing other recording apps.");
            pcm = temporary(".pcm");
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
              throw new IOException("Microphone is unavailable.");
            short[] buffer = new short[2400];
            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(pcm))) {
              while (!stopCapture && !cancel && capturedSamples < WavIO.MAX_SAMPLES) {
                int want = (int) Math.min(buffer.length, WavIO.MAX_SAMPLES - capturedSamples);
                int n = recorder.read(buffer, 0, want, AudioRecord.READ_BLOCKING);
                if (n < 0)
                  throw new IOException(
                      "Microphone read failed (" + n + "). No recording was saved.");
                if (n == 0) continue;
                for (int i = 0; i < n; i++) WavIO.write16(out, buffer[i]);
                capturedSamples += n;
                changed();
              }
            }
            recorder.stop();
            recorder.release();
            recorder = null;
            if (cancel) throw new CancellationException();
            if (capturedSamples == 0)
              throw new IOException("No audio captured. Try recording again.");
            state = State.SAVING;
            changed();
            wav = temporary(".wav");
            try (InputStream in = new FileInputStream(pcm);
                OutputStream out = new BufferedOutputStream(new FileOutputStream(wav))) {
              WavIO.header(out, capturedSamples);
              WavIO.copy(in, out, WavIO.MAX_IMPORT_BYTES, () -> cancel);
            }
            if (cancel) throw new CancellationException();
            RecordingStore.Entry e = store.publish(wav, datedName());
            selectedId = e.id;
            notice =
                capturedSamples >= WavIO.MAX_SAMPLES
                    ? "5-minute limit reached. Original saved."
                    : "Original saved. Ready to clean.";
          } catch (CancellationException ex) {
            notice = "Recording discarded.";
          } catch (Exception ex) {
            failure(ex);
          } finally {
            if (recorder != null) {
              try {
                recorder.stop();
              } catch (Exception ignored) {
              }
              recorder.release();
            }
            if (pcm != null) pcm.delete();
            if (wav != null) wav.delete();
            finish();
          }
        });
  }

  void close() {
    closed = true;
    cancel = true;
    stopCapture = true;
    listener = null;
    worker.shutdown();
  }
}
