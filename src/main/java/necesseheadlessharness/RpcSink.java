package necesseheadlessharness;

import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

import necesse.engine.GameLog;

/**
 * Where a reply goes: one JSON object per line, appended and flushed immediately.
 *
 * <p><strong>A dedicated file rather than stdout.</strong> stdout carries the game's own timestamped
 * log lines, its colour codes, debug prints, and anything any other mod decides to print. The bash
 * runner copes by stripping ANSI and pattern-matching, which is exactly the fragility the reply
 * channel exists to remove -- a driver should never have to parse a game log to learn whether its
 * command worked.
 *
 * <p>Flushing per line matters: the reader is another process polling for a reply, so a line held
 * in a buffer is indistinguishable from a server that has stopped responding.
 *
 * <p>With no path set the reply is printed with a prefix instead, so {@code harness rpc 1 place ...}
 * still does something visible when typed into a live server by hand.
 */
public final class RpcSink {

   /** {@code -Dnecesseheadlessharness.rpc=/path/to/replies.jsonl} */
   public static final String PROPERTY = "necesseheadlessharness.rpc";

   /** Prefix used when there is no file to write to. */
   public static final String STDOUT_PREFIX = "RPC ";

   private static Writer out;

   private static boolean attempted;

   private RpcSink() {
   }

   public static synchronized void emit(String jsonLine) {
      Writer writer = sink();
      if (writer == null) {
         System.out.println(STDOUT_PREFIX + jsonLine);
         return;
      }

      try {
         writer.write(jsonLine);
         writer.write('\n');
         writer.flush();
      } catch (Exception e) {
         // Losing the reply channel is worth a loud complaint: the driver will otherwise sit waiting
         // for a reply that can never arrive, and time out with no explanation.
         GameLog.warn.println("Harness could not write an RPC reply: " + e);
      }
   }

   private static synchronized Writer sink() {
      if (attempted) {
         return out;
      }

      attempted = true;
      String path = System.getProperty(PROPERTY);
      if (path == null || path.trim().isEmpty()) {
         return null;
      }

      try {
         // Append, because the driver may have created the file already, and truncating it would
         // discard a reply written between its open and ours.
         out = new BufferedWriter(new OutputStreamWriter(
            new FileOutputStream(path, true), StandardCharsets.UTF_8));
         GameLog.out.println("Harness RPC replies going to " + path);
      } catch (Exception e) {
         GameLog.warn.println("Harness could not open the RPC reply file " + path + ": " + e);
      }

      return out;
   }
}
