package necesseheadlessharness;



/**
 * Just enough JSON to write a reply line, because the game ships no JSON library -- its {@code lib}
 * folder holds ByteBuddy, JNA and LWJGL and nothing else, and adding a dependency to a mod jar to
 * emit fifteen fields would be out of proportion.
 *
 * <p>Writing only, never parsing: replies go out, commands come in as console lines. That halves
 * the surface and removes the part where a hand-rolled parser would be genuinely unwise.
 *
 * <p><strong>Escaping is the whole point of this class.</strong> Verb output is arbitrary text --
 * item names, failure details, exception messages -- and a single unescaped quote or backslash
 * turns a reply into a parse error on the Python side, which would present as "the harness stopped
 * responding" rather than as a formatting bug. So the escaping is deliberately conservative:
 * everything below 0x20 becomes a {@code \\u} escape rather than being passed through.
 */
public final class Json {

   private Json() {
   }

   /** A string as a quoted, escaped JSON value. Null becomes {@code null}, not {@code "null"}. */
   public static String quote(String value) {
      if (value == null) {
         return "null";
      }

      StringBuilder out = new StringBuilder(value.length() + 8).append('"');
      for (int i = 0; i < value.length(); i++) {
         char c = value.charAt(i);
         switch (c) {
            case '"':
               out.append("\\\"");
               break;
            case '\\':
               out.append("\\\\");
               break;
            case '\n':
               out.append("\\n");
               break;
            case '\r':
               out.append("\\r");
               break;
            case '\t':
               out.append("\\t");
               break;
            case '\b':
               out.append("\\b");
               break;
            case '\f':
               out.append("\\f");
               break;
            default:
               if (c < 0x20) {
                  // Any other control character. Necesse's colour codes live in this range, so this
                  // is not a hypothetical case.
                  out.append(String.format("\\u%04x", (int)c));
               } else {
                  out.append(c);
               }
         }
      }

      return out.append('"').toString();
   }

   /** Builds one flat object. Nested structures go in through {@link #raw}. */
   public static final class Writer {

      private final StringBuilder out = new StringBuilder("{");

      private boolean empty = true;

      public Writer str(String key, String value) {
         return this.raw(key, quote(value));
      }

      public Writer num(String key, long value) {
         return this.raw(key, Long.toString(value));
      }

      public Writer bool(String key, boolean value) {
         return this.raw(key, Boolean.toString(value));
      }

      /** An array of strings, each escaped. */
      public Writer strings(String key, Iterable<String> values) {
         StringBuilder array = new StringBuilder("[");
         boolean first = true;
         for (String value : values) {
            if (!first) {
               array.append(',');
            }

            array.append(quote(value));
            first = false;
         }

         return this.raw(key, array.append(']').toString());
      }

      /** Pre-formatted JSON, for arrays of objects. The caller owns its correctness. */
      public Writer raw(String key, String rawJson) {
         if (!this.empty) {
            this.out.append(',');
         }

         this.out.append(quote(key)).append(':').append(rawJson);
         this.empty = false;
         return this;
      }

      public String end() {
         return this.out.append('}').toString();
      }
   }
}
