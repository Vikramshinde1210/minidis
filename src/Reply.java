import java.util.List;

//** Everything a command can answer with. Mirrors the five RESP2 reply types. */
public sealed interface Reply{

    record Simple(String text) implements Reply {}                   // +Ok
    record Error(String message) implements Reply {}                 // -ERR ...
    record Int(long value) implements  Reply {}                      // :42
    record Bulk(String value)  implements Reply {}                   // $3 foo (null value => $-1, i.e. nil)
    record Array(List<Reply> items) implements Reply {}              // *2 ... (null items => *-1, nil array)

    Reply OK = new Simple("OK");
    Reply NIL = new Bulk(null);
}