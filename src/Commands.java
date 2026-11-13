import org.xml.sax.SAXNotRecognizedException;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class Commands {
    @FunctionalInterface
    private interface Handler { Reply apply(List<String> args); }

    private final Map<String, Handler> handlers = new HashMap<>();
    private final Store store;

    public Commands(Store store){
        this.store = store;
        registerHandlers();
    }

    public Reply execute(List<String> args) {
        if (args.isEmpty()) return new Reply.Error("ERR empty command");
        String name = args.get(0).toUpperCase(Locale.ROOT);
        Handler handler = handlers.get(name);
        if (handler == null) return new Reply.Error("ERR unknown command '" + args.get(0) + "'");
        try {
            return handler.apply(args);
        } catch (CommandException e){
            return new Reply.Error(e.getMessage());
        }
    }

    private void reg(String name, Handler h) {
        handlers.put(name, h);
    }

    private void registerHandlers(){
        reg("PING", a -> a.size() > 1 ? new Reply.Bulk(a.get(1)) : new Reply.Simple("PONG"));
        reg("ECHO", a -> {arity(a, 2); return new Reply.Bulk(a.get(1)); });
        reg("COMMAND", a -> new Reply.Array(List.of())); // redis-cli sends this on connect
        reg("DBSIZE", a -> new Reply.Int(store.size()));
        reg("SET", this::set);
        reg("GET", this::get);
        reg("DEL", this::del);
        reg("EXISTS", this::exists);
    }

    private Reply set(List<String> a){
        arity(a, 3);                                    // options like EX/NX will be added in future commits
        store.put(a.get(1), a.get(2));
        return Reply.OK;
    }

    private Reply get(List<String> a){
        arity(a, 2);
        Object v = store.get(a.get(1));
        if (v == null) return Reply.NIL;
        if(!(v instanceof  String s)) throw wrongType();
        return new Reply.Bulk(s);
    }


    private Reply del(List<String> a){
        if (a.size() < 2) throw new CommandException("ERR wrong number of arguments for 'del'");
        long removed = 0;
        for (String key: a.subList(1, a.size())){
            if (store.delete(key)) removed++;
        }

        return new Reply.Int(removed);
    }

    private Reply exists(List<String> a){
        if (a.size() < 2) throw new CommandException("ERR wrong number of arguments for 'exists'");
        return new Reply.Int(a.subList(1, a.size()).stream().filter(k -> store.get(k) != null).count());
    }


    // ---------------------------- Helpers ------------------------------
    private static void arity(List<String> a, int expected){
        if (a.size() != expected) throw new CommandException("ERR wrong number of arguments for '"+ a.get(0).toLowerCase() + "'");
    }

    private static CommandException wrongType(){
        return new CommandException("WRONGTYPE Operation againts a key holding the wrong kind of value");
    }

    /** Thrown by handlers, turned into a "-ERR .." reply by execute(). No stack trace is needed so it is cheap. */
    private static final class CommandException extends RuntimeException {
        CommandException(String message){
            super(message, null, false, false);
        }
    }
}
