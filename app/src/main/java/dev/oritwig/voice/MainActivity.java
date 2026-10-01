package dev.oritwig.voice;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.text.DateFormat;
import java.util.*;

/** Native, offline product shell. All denoising is delegated to the vendored RNNoise AAR. */
public final class MainActivity extends Activity implements VoiceController.Listener {
  private static final int IMPORT = 10, EXPORT = 11, MICROPHONE = 12;
  private static final int BG = 0xffF4F6F2,
      INK = 0xff18372F,
      MUTED = 0xff536960,
      GREEN = 0xff156C5B,
      PALE = 0xffE1EEE6,
      LINE = 0xffD7E1DA;
  VoiceController controller;
  private LinearLayout body;
  private TextView liveStatus, playbackLabel;
  private ProgressBar progressBar;
  private SeekBar seek;
  private Button playButton;
  private String page = "library", pendingExportId, lastSelected;
  private boolean cleanedSelected, pendingExportClean, preparing, resumed;
  private VoiceController.State renderedState;
  private MediaPlayer player;
  private final Handler ui = new Handler(Looper.getMainLooper());
  private final Runnable playbackTick =
      new Runnable() {
        public void run() {
          if (player != null && !preparing) {
            try {
              if (seek != null) {
                seek.setMax(player.getDuration());
                seek.setProgress(player.getCurrentPosition());
              }
              if (playbackLabel != null)
                playbackLabel.setText(
                    getString(
                        R.string.playback_time,
                        time(player.getCurrentPosition()),
                        time(player.getDuration())));
            } catch (IllegalStateException ignored) {
            }
          }
          ui.postDelayed(this, 250);
        }
      };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    Object retained = getLastNonConfigurationInstance();
    controller =
        retained instanceof VoiceController
            ? (VoiceController) retained
            : new VoiceController(this);
    controller.attach(this);
    if (state != null) {
      page = state.getString("page", "library");
      controller.selectedId = state.getString("selected");
      cleanedSelected = state.getBoolean("cleaned");
      pendingExportId = state.getString("exportId");
      pendingExportClean = state.getBoolean("exportClean");
    }
    render();
    ui.post(playbackTick);
  }

  @Override
  public Object onRetainNonConfigurationInstance() {
    return controller;
  }

  @Override
  protected void onSaveInstanceState(Bundle b) {
    super.onSaveInstanceState(b);
    b.putString("page", page);
    b.putString("selected", controller.selectedId);
    b.putBoolean("cleaned", cleanedSelected);
    b.putString("exportId", pendingExportId);
    b.putBoolean("exportClean", pendingExportClean);
  }

  @Override
  protected void onResume() {
    super.onResume();
    resumed = true;
    if (playButton != null && player == null) playButton.setText(R.string.play_audio);
  }

  @Override
  protected void onStop() {
    resumed = false;
    stopPlayback();
    if (controller.state == VoiceController.State.RECORDING) controller.stopRecording();
    super.onStop();
  }

  @Override
  protected void onDestroy() {
    ui.removeCallbacks(playbackTick);
    stopPlayback();
    controller.attach(null);
    if (!isChangingConfigurations()) controller.close();
    super.onDestroy();
  }

  @Override
  public void changed() {
    boolean same =
        renderedState == controller.state && Objects.equals(lastSelected, controller.selectedId);
    if (same && controller.busy() && liveStatus != null) {
      updateLive();
      return;
    }
    if (renderedState == VoiceController.State.PROCESSING
        && controller.state == VoiceController.State.IDLE
        && controller.error.isEmpty()
        && !controller.cancel) cleanedSelected = true;
    render();
  }

  private int dp(int n) {
    return (int) (n * getResources().getDisplayMetrics().density + .5f);
  }

  private GradientDrawable shape(int color, int stroke, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    if (stroke != 0) d.setStroke(dp(1), stroke);
    return d;
  }

  private LinearLayout vertical() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  private TextView text(String s, int size, int color, boolean bold) {
    TextView t = new TextView(this);
    t.setText(s);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setLineSpacing(dp(3), 1);
    if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    return t;
  }

  private void addText(LinearLayout to, String s, int size, int color, boolean bold) {
    to.addView(text(s, size, color, bold));
  }

  private void gap(LinearLayout to, int h) {
    View v = new View(this);
    to.addView(v, new LinearLayout.LayoutParams(1, dp(h)));
  }

  private LinearLayout card() {
    LinearLayout l = vertical();
    l.setPadding(dp(20), dp(20), dp(20), dp(20));
    l.setBackground(shape(Color.WHITE, LINE, 20));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.bottomMargin = dp(16);
    body.addView(l, p);
    return l;
  }

  private Button button(
      LinearLayout to, String title, String tag, boolean primary, Runnable action) {
    Button b = new Button(this);
    b.setText(title);
    b.setTag(tag);
    b.setAllCaps(false);
    b.setTextSize(16);
    b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    b.setTextColor(primary ? Color.WHITE : GREEN);
    b.setBackground(shape(primary ? GREEN : PALE, 0, 14));
    b.setMinHeight(dp(52));
    b.setPadding(dp(14), dp(10), dp(14), dp(10));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.topMargin = dp(10);
    to.addView(b, p);
    b.setOnClickListener(v -> action.run());
    return b;
  }

  private void render() {
    renderedState = controller.state;
    lastSelected = controller.selectedId;
    liveStatus = null;
    progressBar = null;
    seek = null;
    playbackLabel = null;
    playButton = null;
    if (controller.state == VoiceController.State.RECORDING
        && getPreferences(0).getBoolean("awake", true))
      getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    LinearLayout root = vertical();
    root.setBackgroundColor(BG);
    root.setFitsSystemWindows(true);
    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          v.setPadding(
              insets.getSystemWindowInsetLeft(),
              insets.getSystemWindowInsetTop(),
              insets.getSystemWindowInsetRight(),
              insets.getSystemWindowInsetBottom());
          return insets;
        });
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    body = vertical();
    body.setPadding(dp(24), dp(22), dp(24), dp(30));
    scroll.addView(body);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
    setContentView(root);
    root.requestApplyInsets();
    LinearLayout header = new LinearLayout(this);
    header.setGravity(Gravity.CENTER_VERTICAL);
    TextView title = text("Oritwig Voice", 24, INK, true);
    header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    TextView badge = text("LOCAL ONLY", 10, GREEN, true);
    badge.setPadding(dp(10), dp(8), dp(10), dp(8));
    badge.setBackground(shape(PALE, 0, 18));
    header.addView(badge);
    body.addView(header);
    gap(body, 24);
    if (controller.busy()) {
      renderBusy();
      return;
    }
    if (!controller.error.isEmpty()) {
      LinearLayout c = card();
      addText(c, "Couldn’t finish", 18, 0xff8A3928, true);
      gap(c, 6);
      addText(c, controller.error, 15, INK, false);
      button(
          c,
          "Dismiss",
          "dismissError",
          false,
          () -> {
            controller.clearMessages();
            render();
          });
    } else if (!controller.notice.isEmpty()) {
      TextView n = text(controller.notice, 14, GREEN, false);
      n.setTag("notice");
      body.addView(n);
      gap(body, 16);
    }
    if (page.equals("settings")) {
      renderSettings();
      return;
    }
    if (page.equals("about")) {
      renderAbout();
      return;
    }
    RecordingStore.Entry entry =
        controller.selectedId == null ? null : controller.store.find(controller.selectedId);
    if (entry != null) renderDetail(entry);
    else {
      controller.selectedId = null;
      renderLibrary();
    }
  }

  private void renderLibrary() {
    LinearLayout hero = card();
    addText(hero, "A little less noise.\nA little more you.", 30, INK, true);
    gap(hero, 12);
    addText(
        hero,
        "Clean up spoken audio on your device. Keep the original, hear both versions, and choose"
            + " what to share.",
        16,
        MUTED,
        false);
    gap(hero, 10);
    button(hero, "●  Record speech", "record", true, this::requestRecording);
    button(hero, "Import a WAV file", "import", false, this::chooseImport);
    gap(hero, 12);
    addText(hero, WavIO.SUPPORTED, 12, MUTED, false);
    LinearLayout libraryHeader = new LinearLayout(this);
    TextView h = text("Your recordings", 21, INK, true);
    libraryHeader.addView(h, new LinearLayout.LayoutParams(0, -2, 1));
    addText(libraryHeader, Integer.toString(controller.store.list().size()), 17, MUTED, false);
    body.addView(libraryHeader);
    gap(body, 14);
    List<RecordingStore.Entry> entries = controller.store.list();
    if (entries.isEmpty()) {
      LinearLayout empty = card();
      addText(empty, "Room for your first recording", 17, INK, true);
      gap(empty, 8);
      addText(
          empty,
          "Record a voice note or bring a supported WAV. Everything stays here until you export"
              + " it.",
          15,
          MUTED,
          false);
    }
    for (RecordingStore.Entry e : entries) {
      LinearLayout c = card();
      addText(c, e.name, 19, INK, true);
      gap(c, 6);
      addText(
          c,
          time(e.samples * 1000 / WavIO.RATE)
              + "  ·  "
              + DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(e.created)),
          13,
          MUTED,
          false);
      gap(c, 8);
      addText(
          c,
          e.isClean() ? "Original + cleaned version" : "Original preserved · ready to clean",
          13,
          GREEN,
          true);
      button(
          c,
          "Open recording",
          "open-" + e.id,
          false,
          () -> {
            stopPlayback();
            cleanedSelected = e.isClean();
            controller.select(e.id);
          });
    }
    button(
        body,
        "Settings & privacy",
        "settings",
        false,
        () -> {
          page = "settings";
          controller.clearMessages();
          render();
        });
  }

  private void renderDetail(RecordingStore.Entry e) {
    if (!e.isClean()) cleanedSelected = false;
    button(
        body,
        "‹  Your recordings",
        "backLibrary",
        false,
        () -> {
          stopPlayback();
          controller.selectedId = null;
          controller.clearMessages();
          render();
        });
    gap(body, 18);
    addText(body, e.name, 29, INK, true);
    gap(body, 8);
    addText(body, time(e.samples * 1000 / WavIO.RATE) + "  ·  48 kHz mono PCM16", 14, MUTED, false);
    gap(body, 20);
    LinearLayout compare = card();
    addText(compare, "Listen & compare", 20, INK, true);
    gap(compare, 6);
    addText(compare, "Your original stays unchanged.", 14, MUTED, false);
    button(
        compare,
        (cleanedSelected ? "" : "✓  ") + "Original",
        "original",
        !cleanedSelected,
        () -> {
          stopPlayback();
          cleanedSelected = false;
          render();
        });
    Button cleaned =
        button(
            compare,
            (cleanedSelected ? "✓  " : "") + "Cleaned",
            "cleaned",
            cleanedSelected,
            () -> {
              stopPlayback();
              cleanedSelected = true;
              render();
            });
    cleaned.setEnabled(e.isClean());
    if (!e.isClean()) cleaned.setAlpha(.45f);
    gap(compare, 12);
    playbackLabel = text("0:00 / " + time(e.samples * 1000 / WavIO.RATE), 13, MUTED, false);
    compare.addView(playbackLabel);
    seek = new SeekBar(this);
    seek.setContentDescription("Playback position");
    seek.setMax((int) (e.samples * 1000 / WavIO.RATE));
    compare.addView(seek, new LinearLayout.LayoutParams(-1, dp(48)));
    seek.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onProgressChanged(SeekBar s, int value, boolean user) {
            if (user && player != null && !preparing) player.seekTo(value);
          }

          public void onStartTrackingTouch(SeekBar s) {}

          public void onStopTrackingTouch(SeekBar s) {}
        });
    playButton =
        button(
            compare,
            "▶  Play " + (cleanedSelected ? "cleaned" : "original"),
            "play",
            true,
            () -> play(e));
    if (!e.isClean()) {
      LinearLayout clean = card();
      addText(clean, "Reduce background noise", 20, INK, true);
      gap(clean, 8);
      addText(
          clean,
          "RNNoise is designed for speech. It can change the sound of a voice and won’t remove"
              + " every sound. Compare before exporting.",
          15,
          MUTED,
          false);
      button(
          clean,
          "Clean this recording",
          "clean",
          true,
          () -> {
            stopPlayback();
            controller.clean(e);
          });
    }
    LinearLayout files = card();
    addText(files, "Keep the version you like", 20, INK, true);
    gap(files, 8);
    addText(files, "Export creates a WAV copy in the location you choose.", 14, MUTED, false);
    button(
        files,
        "Export " + (cleanedSelected ? "cleaned" : "original") + " WAV",
        "export",
        true,
        () -> chooseExport(e));
    button(files, "Rename recording", "rename", false, () -> rename(e));
    button(files, "Delete local recording", "delete", false, () -> delete(e));
  }

  private void renderBusy() {
    LinearLayout c = card();
    String title;
    switch (controller.state) {
      case RECORDING:
        title = "Recording your voice";
        break;
      case SAVING:
        title = "Saving your original";
        break;
      case IMPORTING:
        title = "Bringing in your WAV";
        break;
      case PROCESSING:
        title = "Cleaning your recording";
        break;
      default:
        title = "Exporting your WAV";
    }
    addText(c, title, 28, INK, true);
    gap(c, 16);
    liveStatus = text("", 32, GREEN, true);
    liveStatus.setTag("liveStatus");
    c.addView(liveStatus);
    gap(c, 16);
    progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    progressBar.setMax(100);
    progressBar.setIndeterminate(
        controller.state != VoiceController.State.PROCESSING
            && controller.state != VoiceController.State.RECORDING);
    c.addView(progressBar, new LinearLayout.LayoutParams(-1, dp(12)));
    gap(c, 20);
    if (controller.state == VoiceController.State.RECORDING) {
      addText(
          c,
          "Microphone is on. Recording stops and saves when you leave this screen. Maximum 5"
              + " minutes.",
          16,
          MUTED,
          false);
      button(c, "■  Stop & save original", "stopRecord", true, controller::stopRecording);
      button(
          c,
          "Discard this take",
          "discard",
          false,
          () ->
              new AlertDialog.Builder(this)
                  .setTitle("Discard this take?")
                  .setMessage("This recording has not been saved.")
                  .setNegativeButton("Keep recording", null)
                  .setPositiveButton("Discard", (d, w) -> controller.cancel())
                  .show());
    } else {
      addText(
          c,
          controller.state == VoiceController.State.PROCESSING
              ? "RNNoise runs entirely on this device. Your original is safe."
              : "Please keep the app open while this finishes.",
          16,
          MUTED,
          false);
      button(c, "Cancel", "cancelJob", false, controller::cancel);
    }
    updateLive();
  }

  private void updateLive() {
    if (liveStatus == null) return;
    if (controller.cancel) {
      liveStatus.setText(R.string.cancelling);
      return;
    }
    if (controller.state == VoiceController.State.RECORDING) {
      liveStatus.setText(time(controller.capturedSamples * 1000 / WavIO.RATE));
      progressBar.setProgress((int) (100 * controller.capturedSamples / WavIO.MAX_SAMPLES));
    } else if (controller.state == VoiceController.State.PROCESSING) {
      liveStatus.setText(getString(R.string.percent, controller.progress));
      progressBar.setProgress(controller.progress);
    } else liveStatus.setText(R.string.one_moment);
  }

  private void requestRecording() {
    if (controller.busy()) return;
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        == PackageManager.PERMISSION_GRANTED) {
      stopPlayback();
      controller.record();
      return;
    }
    new AlertDialog.Builder(this)
        .setTitle("Use your microphone?")
        .setMessage(
            "Oritwig Voice records only when you tap Record. Audio stays on this device. Leaving"
                + " the app stops and saves the recording. You can import a WAV without microphone"
                + " access.")
        .setNegativeButton("Not now", null)
        .setPositiveButton(
            "Continue",
            (d, w) ->
                requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, MICROPHONE))
        .show();
  }

  @Override
  public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
    super.onRequestPermissionsResult(request, permissions, grants);
    if (request == MICROPHONE) {
      if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
        if (resumed) controller.record();
        else {
          controller.notice = "Microphone access enabled. Tap Record when you’re ready.";
          render();
        }
      } else {
        controller.notice =
            "Microphone access wasn’t enabled. Importing WAV files is still available.";
        render();
      }
    }
  }

  private void chooseImport() {
    stopPlayback();
    Intent intent =
        new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .setType("*/*")
            .addCategory(Intent.CATEGORY_OPENABLE);
    intent.putExtra(
        Intent.EXTRA_MIME_TYPES,
        new String[] {
          "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave", "application/octet-stream"
        });
    try {
      startActivityForResult(intent, IMPORT);
    } catch (ActivityNotFoundException ex) {
      controller.error = "No system file picker is available on this device.";
      render();
    }
  }

  private void chooseExport(RecordingStore.Entry e) {
    stopPlayback();
    pendingExportId = e.id;
    pendingExportClean = cleanedSelected;
    Intent intent =
        new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .setType("audio/wav")
            .addCategory(Intent.CATEGORY_OPENABLE);
    String name = e.name.replaceAll("[^\\p{L}\\p{N} ._-]", "_");
    intent.putExtra(
        Intent.EXTRA_TITLE, name + (cleanedSelected ? "-cleaned" : "-original") + ".wav");
    try {
      startActivityForResult(intent, EXPORT);
    } catch (ActivityNotFoundException ex) {
      controller.error = "No system file picker is available on this device.";
      render();
    }
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request != IMPORT && request != EXPORT) return;
    if (result != RESULT_OK || data == null || data.getData() == null) {
      controller.notice = request == IMPORT ? "Import cancelled." : "Export cancelled.";
      pendingExportId = null;
      render();
      return;
    }
    Uri uri = data.getData();
    if (request == IMPORT) {
      String name = "Imported speech";
      try (android.database.Cursor c =
          getContentResolver()
              .query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
        if (c != null && c.moveToFirst()) name = c.getString(0);
      } catch (Exception ignored) {
      }
      if (name != null && name.toLowerCase(Locale.ROOT).endsWith(".wav"))
        name = name.substring(0, name.length() - 4);
      page = "library";
      cleanedSelected = false;
      controller.importWav(uri, name);
    } else {
      RecordingStore.Entry e = controller.store.find(pendingExportId);
      if (e != null) controller.export(e, pendingExportClean, uri);
      else {
        controller.error =
            "The recording is no longer available. Choose a recording and export again.";
        render();
      }
      pendingExportId = null;
    }
  }

  private void play(RecordingStore.Entry e) {
    if (preparing) return;
    if (player != null) {
      if (player.isPlaying()) {
        player.pause();
        playButton.setText(R.string.resume);
      } else {
        player.start();
        playButton.setText(R.string.pause);
      }
      return;
    }
    try {
      player = new MediaPlayer();
      preparing = true;
      playButton.setText(R.string.preparing);
      player.setDataSource((cleanedSelected ? e.cleaned() : e.original()).getAbsolutePath());
      player.setLooping(getPreferences(0).getBoolean("loop", false));
      player.setOnPreparedListener(
          p -> {
            if (player != p) return;
            preparing = false;
            p.start();
            if (playButton != null) playButton.setText(R.string.pause);
          });
      player.setOnCompletionListener(
          p -> {
            stopPlayback();
            if (playButton != null) playButton.setText(R.string.play_again);
          });
      player.setOnErrorListener(
          (p, what, extra) -> {
            stopPlayback();
            controller.error = "Playback could not open this WAV. The saved audio is unchanged.";
            render();
            return true;
          });
      player.prepareAsync();
    } catch (Exception ex) {
      stopPlayback();
      controller.error = "Playback could not start: " + ex.getMessage();
      render();
    }
  }

  private void stopPlayback() {
    if (player != null) {
      player.release();
      player = null;
    }
    preparing = false;
  }

  private void rename(RecordingStore.Entry e) {
    EditText input = new EditText(this);
    input.setSingleLine(true);
    input.setText(e.name);
    input.setSelectAllOnFocus(true);
    input.setContentDescription("Recording name");
    input.setFilters(
        new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(80)});
    new AlertDialog.Builder(this)
        .setTitle("Name this recording")
        .setView(input)
        .setNegativeButton("Cancel", null)
        .setPositiveButton(
            "Save",
            (d, w) -> {
              try {
                controller.store.rename(e, input.getText().toString());
              } catch (IOException ex) {
                controller.error = ex.getMessage();
              }
              render();
            })
        .show();
  }

  private void delete(RecordingStore.Entry e) {
    new AlertDialog.Builder(this)
        .setTitle("Delete local recording?")
        .setMessage(
            "This permanently deletes “"
                + e.name
                + "” and its cleaned version from this app. Files you exported elsewhere are not"
                + " affected.")
        .setNegativeButton("Keep", null)
        .setPositiveButton(
            "Delete",
            (d, w) -> {
              stopPlayback();
              try {
                controller.store.delete(e);
                controller.selectedId = null;
                controller.notice = "Local recording deleted.";
              } catch (IOException ex) {
                controller.error = ex.getMessage();
              }
              render();
            })
        .show();
  }

  private void renderSettings() {
    button(
        body,
        "‹  Your recordings",
        "backSettings",
        false,
        () -> {
          page = "library";
          render();
        });
    gap(body, 20);
    addText(body, "Settings & privacy", 28, INK, true);
    gap(body, 18);
    LinearLayout c = card();
    addText(c, "Listening & recording", 19, INK, true);
    setting(c, "Loop playback", "loop", false);
    setting(c, "Keep screen awake while recording", "awake", true);
    gap(c, 12);
    addText(
        c,
        "Capture: 48 kHz mono PCM16, up to 5 minutes. Android’s voice-recognition microphone source"
            + " is used. Device audio processing may vary.",
        14,
        MUTED,
        false);
    c = card();
    addText(c, "Private by design", 19, INK, true);
    gap(c, 10);
    addText(
        c,
        "No account. No internet permission. No analytics or ads. Recordings are stored in this"
            + " app’s private storage; Android backup and device transfer are disabled."
            + " Uninstalling removes local recordings. Export a copy if you want to keep it.",
        15,
        MUTED,
        false);
    gap(c, 10);
    addText(
        c,
        "Only microphone permission is requested, after you opt in. The system file picker grants"
            + " access only to the files you choose. An export provider may upload files using its"
            + " own account and settings.",
        15,
        MUTED,
        false);
    button(
        body,
        "About & open-source licenses",
        "about",
        false,
        () -> {
          page = "about";
          render();
        });
  }

  private void setting(LinearLayout parent, String label, String key, boolean def) {
    Switch s = new Switch(this);
    s.setText(label);
    s.setTextColor(INK);
    s.setTextSize(15);
    s.setPadding(0, dp(14), 0, dp(14));
    s.setMinHeight(dp(56));
    s.setChecked(getPreferences(0).getBoolean(key, def));
    parent.addView(s, new LinearLayout.LayoutParams(-1, -2));
    s.setOnCheckedChangeListener((v, on) -> getPreferences(0).edit().putBoolean(key, on).apply());
  }

  private void renderAbout() {
    button(
        body,
        "‹  Settings",
        "backAbout",
        false,
        () -> {
          page = "settings";
          render();
        });
    gap(body, 20);
    addText(body, "Built around RNNoise", 28, INK, true);
    gap(body, 18);
    LinearLayout c = card();
    addText(c, "Oritwig Voice · 0.1.0 prototype", 19, INK, true);
    gap(c, 12);
    addText(
        c,
        "The actual denoising engine and default model are the unchanged Xiph/Mozilla RNNoise"
            + " dependency shipped in Telegram for Android at commit"
            + " f2908b14133bbffbf7ab04f641ecb5bfaf533242. RNNoise was not authored by Telegram."
            + " Telegram’s client, accounts and network code are not included.",
        15,
        MUTED,
        false);
    gap(c, 12);
    addText(
        c,
        "This app adds recording, a local library, WAV framing, playback and export around that"
            + " mature engine. It does not transcribe, separate speakers, restore music or"
            + " guarantee clearer speech.",
        15,
        MUTED,
        false);
    gap(c, 12);
    addText(
        c,
        "End-of-stream policy: zero-pad the last 10 ms frame, flush one zero frame, omit the first"
            + " delayed output frame, then trim to the original sample count. Original audio is"
            + " preserved byte for byte.",
        14,
        MUTED,
        false);
    button(body, "Read license notices", "licenses", false, this::licenses);
  }

  private void licenses() {
    try (InputStream in = getAssets().open("THIRD_PARTY_NOTICES.txt")) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] b = new byte[4096];
      int n;
      while ((n = in.read(b)) != -1) out.write(b, 0, n);
      TextView t = text(out.toString("UTF-8"), 12, INK, false);
      t.setPadding(dp(20), dp(12), dp(20), dp(12));
      ScrollView scroll = new ScrollView(this);
      scroll.addView(t);
      new AlertDialog.Builder(this)
          .setTitle("Open-source notices")
          .setView(scroll)
          .setPositiveButton("Done", null)
          .show();
    } catch (IOException ex) {
      controller.error = "License notices could not be opened.";
      render();
    }
  }

  @Override
  public void onBackPressed() {
    if (controller.busy()) {
      new AlertDialog.Builder(this)
          .setTitle(
              controller.state == VoiceController.State.RECORDING
                  ? "Stop recording?"
                  : "Cancel this operation?")
          .setMessage("Your saved originals will stay safe.")
          .setNegativeButton("Keep going", null)
          .setPositiveButton(
              controller.state == VoiceController.State.RECORDING
                  ? "Stop & save"
                  : "Cancel operation",
              (d, w) -> {
                if (controller.state == VoiceController.State.RECORDING) controller.stopRecording();
                else controller.cancel();
              })
          .show();
    } else if (!page.equals("library")) {
      page = page.equals("about") ? "settings" : "library";
      render();
    } else if (controller.selectedId != null) {
      stopPlayback();
      controller.selectedId = null;
      controller.clearMessages();
      render();
    } else super.onBackPressed();
  }

  static String time(long millis) {
    long seconds = Math.max(0, millis / 1000);
    return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
  }
}
