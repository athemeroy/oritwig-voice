import dev.oritwig.voice.SpeechProcessor;
import dev.oritwig.voice.WavIO;
import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
/** Runs the app's unmodified file adapter against the real host RNNoise JNI. */
public final class HostFileSmoke {
  public static void main(String[] args) throws Exception {
    File in=new File(args[0]),out=new File(args[1]);
    byte[] before=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(in.toPath()));
    if(out.exists())throw new IllegalStateException("Refusing to overwrite output");
    SpeechProcessor.process(in,out,()->false,p->{});
    long a=WavIO.inspect(in).samples,b=WavIO.inspect(out).samples;
    if(a!=b)throw new AssertionError("Sample count changed");
    if(!Arrays.equals(before,MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(in.toPath()))))throw new AssertionError("Original changed");
    if(Arrays.equals(Files.readAllBytes(in.toPath()),Files.readAllBytes(out.toPath())))throw new AssertionError("Engine did not change output");
    System.out.println("PASS: actual app file adapter + real host RNNoise JNI; original unchanged; input/output samples="+a+"; duration="+(a/48000.0)+" s");
    System.out.println("Host processing only. No physical microphone or perceptual-quality claim.");
  }
}
