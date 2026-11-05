import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** RESP2 (Redis Serialization protocol) codec: parse requests, serialize replies.
 *
 * bytes in ( from socket) -> RESP.readCommand -> List<String> -> Commands.execute -> Reply
 * Resp.write -> bytes out (socket)
 *
 * */
public class Resp {
    /** Same limits reids uses: protects the server from a client announcing absurd sizes. */
    public static final int MAX_ARGS = 1_048_576;
    public static final int MAX_BULK = 512 * 1024 * 1024;
    public static final int MAX_LINE = 64 * 1024;
    public static final int CHUNK = 64 * 1024;

    private Resp() {}

    /** The client send something that is not valid RESP. The server replies with an error and hangs up. */
    public static final class ProtocolException extends IOException {
        public ProtocolException(String message){
            super(message);
        }
    }

    /**
     * Reads one command. Returns null on clean EOF.
     * Normal clients send and array of bulk strings: *2\r\n$3\r\nGET\r\n$1\r\nk\r\n
     * We also accept "inline" commands (a plain text line) so you can use telnet/nc.
     */
    public static List<String> readCommand(DataInputStream in) throws IOException {
        int first = in.read();
        if (first == -1) return null;
        if (first != '*') {
            String line = (char) first + readline(in);
            String trimmed = line.trim();
            return trimmed.isEmpty() ? List.of() : Arrays.asList(trimmed.split("\\s+"));
        }
        int count = parseLength(readline(in), "invalid multibulk length");
        if (count > MAX_ARGS) throw new ProtocolException("invalid multibulk length");
        List<String> args = new ArrayList<>(Math.max(0, Math.min(count, 1024)));
        for (int i = 0; i < count; i++) {
            int type = in.read();
            if (type != '$') throw new ProtocolException("expected '$', got '" + (char) type + "'");
            int len = parseLength(readline(in), "invalid bulk length");
            if (len < 0 || len > MAX_BULK) throw new ProtocolException("invalid bulk length");
            byte[] buf = readBytes(in , len);
            if(in.read() != '\r' || in.read() != '\n') throw new ProtocolException("missing CRLF afterbulk string");
            args.add(new String(buf, StandardCharsets.UTF_8));
        }
        return args;
    }

    private static byte[] readBytes(DataInputStream in, int len) throws IOException{
        if (len <= CHUNK) {
            byte[] buf = new byte[len];
            in.readFully(buf);
            return buf;
        }
        ByteArrayOutputStream acc = new ByteArrayOutputStream(CHUNK);
        byte[] chunk = new byte[CHUNK];
        int remaining = len;
        while (remaining > 0){
            int n = Math.min(CHUNK, remaining);
            in.readFully(chunk, 0, n);
            acc.write(chunk, 0, n);
            remaining -= n;
        }
        return acc.toByteArray();
    }


    public static int parseLength(String s, String error) throws ProtocolException{
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e){
            throw new ProtocolException(error);
        }
    }

    private static String readline(DataInputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while((c = in.read()) != -1) {
            if (c == '\r') {
                if (in.read() != '\n') throw new ProtocolException("bare CR");
                return sb.toString();
            }
            sb.append((char) c);
            if (sb.length() > MAX_LINE) throw new ProtocolException("too big inline request");
        }
        throw new EOFException();
    }

    /** Serialise a reply , e.g Int(5) becomes ":5\r\n". */
    public static void write(OutputStream out, Reply reply) throws IOException {
        switch (reply) {
            case Reply.Simple s -> line(out, "+" + s.text());
            case Reply.Error e -> line(out, "-" + e.message());
            case Reply.Int i -> line(out, ":" + i.value());
            case Reply.Bulk b -> {
                if (b.value() == null) {
                    line(out, "$-1");  // nil
                } else {
                    byte[] bytes = b.value().getBytes(StandardCharsets.UTF_8);
                    line(out, "$" + bytes.length);
                    out.write(bytes);
                    out.write('\r');
                    out.write('\n');
                }
            }
            case Reply.Array a -> {
                if (a.items() == null) {
                    line(out, "*-1");  // nil array
                } else {
                    line(out, "*" + a.items().size());
                    for (Reply item: a.items()) write(out, item);
                }
            }
        }
    }

    /** Serialise a command as a RESO array of bulk strings (used for the AOF file). */
    public static void writeCommand(OutputStream out, List<String> args) throws IOException {
        write(out, new Reply.Array(args.stream().<Reply>map(Reply.Bulk::new).toList()));
    }

    private static void line(OutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.UTF_8));
        out.write('\r');
        out.write('\n');
    }
}
