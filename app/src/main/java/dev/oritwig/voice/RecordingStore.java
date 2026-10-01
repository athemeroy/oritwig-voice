package dev.oritwig.voice;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONObject;

/** App-private local library. Original WAVs are never rewritten by processing. */
public final class RecordingStore {
  final File root;

  public RecordingStore(Context context) {
    root = new File(context.getFilesDir(), "recordings");
    root.mkdirs();
  }

  public static final class Entry {
    public final String id;
    public String name;
    public final long created, samples;
    public final File directory;

    Entry(File d, String id, String name, long created, long samples) {
      this.directory = d;
      this.id = id;
      this.name = name;
      this.created = created;
      this.samples = samples;
    }

    public File original() {
      return new File(directory, "original.wav");
    }

    public File cleaned() {
      return new File(directory, "cleaned.wav");
    }

    public boolean isClean() {
      return cleaned().isFile();
    }
  }

  public List<Entry> list() {
    List<Entry> entries = new ArrayList<>();
    File[] dirs = root.listFiles();
    if (dirs != null)
      for (File d : dirs) {
        if (d.getName().startsWith(".")) continue;
        try {
          String json =
              new String(
                  java.nio.file.Files.readAllBytes(new File(d, "entry.json").toPath()),
                  StandardCharsets.UTF_8);
          JSONObject obj = new JSONObject(json);
          Entry e =
              new Entry(
                  d,
                  d.getName(),
                  obj.getString("name"),
                  obj.getLong("created"),
                  obj.getLong("samples"));
          if (e.original().isFile()) entries.add(e);
        } catch (Exception ignored) {
          /* Incomplete transactions are not published. */
        }
      }
    entries.sort((a, b) -> Long.compare(b.created, a.created));
    return entries;
  }

  public Entry find(String id) {
    for (Entry e : list()) if (e.id.equals(id)) return e;
    return null;
  }

  public Entry publish(File wav, String name) throws IOException {
    WavIO.Info info = WavIO.inspect(wav);
    String id = UUID.randomUUID().toString();
    File staging = new File(root, ".pending-" + id);
    if (!staging.mkdir()) throw new IOException("Cannot create local recording.");
    Entry e = new Entry(staging, id, cleanName(name), System.currentTimeMillis(), info.samples);
    try {
      if (!wav.renameTo(e.original())) throw new IOException("Cannot save original recording.");
      save(e);
      File destination = new File(root, id);
      if (!staging.renameTo(destination)) throw new IOException("Cannot finish saving recording.");
      return new Entry(destination, id, e.name, e.created, e.samples);
    } catch (IOException ex) {
      removeTree(staging);
      throw ex;
    }
  }

  public void rename(Entry e, String name) throws IOException {
    String previous = e.name;
    e.name = cleanName(name);
    try {
      save(e);
    } catch (IOException ex) {
      e.name = previous;
      throw ex;
    }
  }

  private void save(Entry e) throws IOException {
    try {
      JSONObject obj = new JSONObject();
      obj.put("name", e.name);
      obj.put("created", e.created);
      obj.put("samples", e.samples);
      File next = new File(e.directory, "entry.json.tmp");
      try (FileOutputStream out = new FileOutputStream(next)) {
        out.write(obj.toString().getBytes(StandardCharsets.UTF_8));
        out.getFD().sync();
      }
      if (!next.renameTo(new File(e.directory, "entry.json")))
        throw new IOException("Cannot update recording name.");
    } catch (org.json.JSONException ex) {
      throw new IOException("Cannot save recording details.", ex);
    }
  }

  public void delete(Entry e) throws IOException {
    if (!removeTree(e.directory)) throw new IOException("Some local files could not be removed.");
  }

  static boolean removeTree(File f) {
    boolean ok = true;
    File[] children = f.listFiles();
    if (children != null) for (File c : children) ok = removeTree(c) && ok;
    return f.delete() && ok;
  }

  static String cleanName(String name) {
    String s = name == null ? "" : name.replaceAll("[\\p{Cntrl}]", " ").trim();
    if (s.isEmpty()) s = "Untitled speech";
    return s.substring(0, Math.min(80, s.length()));
  }
}
