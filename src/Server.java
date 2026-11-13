import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;

/**
 * Commit 2: an echo server.
 * One platform thread accepts the connection; evert client gets its own virtual thread.
 * There are no real commands yet: whatever the client sends is echoed back as a bulk string.
 */

public class Server {
    private final ServerSocket serverSocket;
    public Server(int port) throws IOException {
        this.serverSocket = new ServerSocket(port);
    }

    public void start(){
        Thread.ofPlatform().name("acceptor").daemon(true).start(this::acceptLoop);
    }

    private void acceptLoop(){
        while (!serverSocket.isClosed()){
            try {
                Socket client = serverSocket.accept();          // blocks until client connects
                Thread.ofVirtual().start(() -> serve(client));  // one cheap virtual thread per client
            } catch (IOException e){
                return;                                         // socket closed
            }
        }
    }

    private void serve(Socket socket){
        try(socket;
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            OutputStream out = new BufferedOutputStream(socket.getOutputStream())) {
            List<String> command;
            while ((command = Resp.readCommand(in)) != null){           // null = client closed the connection
                if (command.isEmpty()) continue;
                Reply reply = new Reply.Bulk(String.join(" ", command));
                Resp.write(out, reply);
                out.flush();
            }
        } catch (IOException e){
            // client went away or sent garbage: drop the connection
        }
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]): 7379;
        Server server = new Server(port);
        server.start();
        System.out.println("echo server listening on "+ port);
        Thread.currentThread().join();                            // keep main alive; the acceptor is daemon thread
    }
}
