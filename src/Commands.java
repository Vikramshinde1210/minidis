import java.util.*;
import java.util.regex.Pattern;

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
        reg("INCR", a -> { arity(a, 2); return incrBy(a.get(1), 1);});
        reg("DECR", a -> { arity(a, 2); return incrBy(a.get(1), -1);});
        reg("INCRBY", a -> { arity(a, 3); return incrBy(a.get(1), parseLong(a.get(2)));});
        reg("EXPIRE", a -> { arity(a, 3); return pexpireAt(a.get(1), store.now() +  parseLong(a.get(2)) * 1000);});
        reg("PEXPIREAT", a -> { arity(a, 3); return pexpireAt(a.get(1), store.now() +  parseLong(a.get(2)));});
        reg("TTL", a -> { arity(a, 2); return ttl(a.get(1));});
        reg("KEYS", a -> { arity(a, 2); return keys(a.get(1));});
        reg("LPUSH", a -> push(a, true));
        reg("RPUSH", a -> push(a, false));
        reg("LPOP", a -> { arity(a, 2); return pop(a.get(1));});
        reg("LLEN", a -> { arity(a, 2); return new Reply.Int(list(a.get(1), false).size());});
        reg("LRANGE", a -> {arity(a,4); return lrange(a.get(1), parseLong(a.get(2)), parseLong(a.get(3)));});
        reg("HSET", this::hset);
        reg("HGET", a -> {arity(a, 3); return hget(a.get(1), a.get(2));});
        reg("HGETALL", a -> {arity(a, 2); return hgetall(a.get(1));});
    }

    private Reply set(List<String> a){
        if (a.size() < 3) throw new CommandException("ERR wrong number of arguments for 'set'");
        String  key = a.get(1), value = a.get(2);
        Long deadline = null;
        boolean nx = false;
        for (int i = 3; i < a.size(); i++){
            switch (a.get(i).toUpperCase(Locale.ROOT)){
                case "EX" -> deadline = store.now() + parseLong(next(a, ++i)) * 1000;   // seconds
                case "PX" -> deadline = store.now() + parseLong(next(a, ++i));          // milli sec
                case "NX" -> nx = true;                                                 // only if absent
                default -> throw new CommandException("ERR syntax error");
            }
        }
        if (nx && store.get(key) != null) return Reply.NIL;
        store.put(key, value);                                                          // clears any old TTL
        if (deadline != null) store.expireAT(key, deadline);
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

    private Reply incrBy(String key, long delta){
        Object v = store.get(key);
        long current = 0;                       // a missing key count as zero
        if( v != null) {
            if( !(v instanceof String s)) throw wrongType();
            current = parseLong(s);            // "abc" -> err
        }
        long updated;
        try{
            updated = Math.addExact(current, delta); // throws instead of silently wrapping around
        } catch (ArithmeticException e){
            throw new CommandException("ERR increment or decrement would overflow");
        }
        if (v == null) store.put(key, String.valueOf(updated));
        else store.putKeepTtl(key, String.valueOf(updated));
        return new Reply.Int(updated);
    }

    // ---------------------------- lists -------------------------------------
    private Reply push(List<String> a, boolean left){
        if(a.size() < 3) throw new CommandException("ERR wrong number of arguments for '" + a.get(0).toLowerCase(Locale.ROOT) + "'");
        Deque<String> list = list(a.get(1), true);
        for (String v: a.subList(2, a.size())){
            if (left) list.addFirst(v); else list.addLast(v);
        }
        return new Reply.Int(list.size());
    }

    private Reply pop(String key){
        Deque<String> list = list(key, false);
        if (list.isEmpty()) return Reply.NIL;
        String v = list.pollFirst();
        if (list.isEmpty()) store.delete(key);       // Redis never keeps and empty list
        return new Reply.Bulk(v);
    }

    private Reply lrange(String key, long start, long stop){
        List<String> all = new ArrayList<>(list(key, false));
        int n = all.size();
        long from = start < 0 ? Math.max(0, n + start): start;  // negative = counted from the end
        long to = stop < 0 ? n + stop : Math.min(stop, n-1);
        if (from > to || from >= n) return new Reply.Array(List.of());
        return bulks(all.subList((int) from, (int) to + 1)); // stop is inclusive
    }

    /** Returns the LIVE list stored under the key (not a copy). */
    @SuppressWarnings("unchecked")
    private Deque<String> list(String key, boolean create){
        Object v = store.get(key);
        if (v == null){
            Deque<String> fresh = new ArrayDeque<>();
            if (create) store.put(key, fresh);
            return fresh;
        }
        if (!(v instanceof Deque<?>)) throw wrongType();
        return (Deque<String>) v;
    }

    // ---------------------------- Hashes ------------------------------

    private Reply hset(List<String> a){
        if (a.size() < 4 || a.size() % 2 != 0) throw new ClassCastException("ERR wrong number of arguments for 'hset'");
        Map<String, String> h = hash(a.get(1), true);
        long added = 0;
        for (int i = 2; i < a.size(); i++) {
            if (h.put(a.get(i), a.get(i+1)) == null) added++;        // only NEW fields count
        }
        return new Reply.Int(added);
    }

    private Reply hget(String key, String field){
        String v = hash(key, false).get(field);
        return v == null ? Reply.NIL : new Reply.Bulk(v);
    }

    private Reply hgetall(String key){
        List<Reply> flat = new ArrayList<>();
        hash(key, false).forEach((f, v) -> {flat.add(new Reply.Bulk(f)); flat.add(new Reply.Bulk(v)); });
        return new Reply.Array(flat);                         // field, value, field , value
    }

    /** Returns the LIVE Hash stored under the key (not a copy). */
    @SuppressWarnings("unchecked")
    private Map<String, String> hash(String key, boolean create){
        Object v = store.get(key);
        if (v == null){
            Map<String, String> fresh = new LinkedHashMap<>();
            if (create) store.put(key, fresh);
            return fresh;
        }
        if(!(v instanceof Map<?,?>)) throw wrongType();
        return (Map<String, String>) v;
    }

    // ---------------------------- Helpers ------------------------------
    private static void arity(List<String> a, int expected){
        if (a.size() != expected) throw new CommandException("ERR wrong number of arguments for '"+ a.get(0).toLowerCase() + "'");
    }

    private static long parseLong(String s){
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e){
            throw new CommandException("ERR value is not an integer or out of range");
        }
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

    private static String next(List<String> a, int i){
        if(i > a.size()) throw new CommandException("ERR syntax error");
        return a.get(i);
    }

    private static Reply bulks(List<String> items){
        return new Reply.Array(items.stream().<Reply>map(Reply.Bulk::new).toList());
    }

    // -------------------------- expiry ------------------------------------------
    private Reply pexpireAt(String key, long epochMillis){
        if (store.get(key) == null) return new Reply.Int(0);
        store.expireAT(key, epochMillis);
        return new Reply.Int(1);
    }

    private Reply ttl(String key){
        if (store.get(key) == null) return new Reply.Int(-2);    // no such key
        Long dealine = store.expiryOf(key);
        if (dealine == null) return new Reply.Int(-1);           // key exists, no TTL
        return new Reply.Int(Math.max(0, (dealine - store.now() + 999) / 1000)); // round up to whole seconds
    }

    private Reply keys(String glob){
        Pattern p = Pattern.compile(glob.replace("?",".").replace("*", ".*"), Pattern.DOTALL);
        return new Reply.Array(store.keys().stream().filter(k -> p.matcher(k).matches()).
                <Reply>map(Reply.Bulk::new).toList());
    }

    public void sweepExpired(){
        store.evictExpired(20);
    }
}
