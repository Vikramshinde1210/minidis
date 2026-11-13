import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * The keyspace. Deliberately NOT thread safe: only the single command executor thread
 * ever touches it so there are no locks anywhere
 */
public final class Store {
    private final Map<String, Object> data = new HashMap<>();
    private final Map<String, Long> expiredAt = new HashMap<>();  // key -> epoch millis
    private final LongSupplier clock;

    public Store(LongSupplier clock) {
        this.clock = clock;
    }

    /** Lazy expiration: every read first checks whether the key is already dead. */
    public Object get(String key) {
        Long deadline = expiredAt.get(key);
        if(deadline != null && deadline <= clock.getAsLong()){
            delete(key);
            return null;
        }
        return data.get(key);
    }

    /** Plain overwrite. Like redis SET, this clears any previous TTL. */
    public void put(String key, Object value) {
        data.put(key, value);
        expiredAt.remove(key);
    }

    /** Overwrite the value but keep the TTL ( INCR needs this). */
    public void putKeepTtl(String key, Object value) { data.put(key, value); }

    public boolean delete(String key) {
        expiredAt.remove(key);
        return data.remove(key) != null;
    }

    // raw count, including keys that are dead but have not been noticed yet
    public int size() { return data.size(); }

    public void expireAT(String key, long epochMillis) { expiredAt.put(key, epochMillis); }

    public Long expiryOf(String key) { return expiredAt.get(key); }

    public long now() { return clock.getAsLong(); }

    public List<String> keys(){
        return new ArrayList<>(data.keySet()).stream().filter(k -> get(k) != null).toList();
    }

    /**
     * Active expiration: look at up to {@code sample} keys that have a TTL and evict dead ones.
     * Lazy expiration alone would leak memory for keys that are never read again.
     * ( This version always looks at the same first N entries, future implementation will make it random pickup
     */
    public int evictExpired(int sample){
        long now = clock.getAsLong();
        List<String> dead = new ArrayList<>();
        int seen = 0;
        for (Map.Entry<String, Long> e: expiredAt.entrySet()){
            if (seen++ >= sample) break;
            if (e.getValue() <= now) dead.add(e.getKey());
        }
        dead.forEach(this::delete);
        return dead.size();
    }

}
