import java.util.HashMap;
import java.util.Map;

/**
 * The keyspace. Deliberately NOT thread safe: only the single command executor thread
 * ever touches it so there are no locks anywhere
 */
public final class Store {
    private final Map<String, Object> data = new HashMap<>();
    public Object get(String key) { return data.get(key); }
    public void put(String key, Object value) { data.put(key, value); }
    public boolean delete(String key) { return data.remove(key) != null; }
    public int size() { return data.size(); }
}
