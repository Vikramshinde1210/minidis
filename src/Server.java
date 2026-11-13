import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

/**
 * Commit: an echo server.
 * One platform thread accepts the connection; evert client gets its own virtual thread.
 * There are no real commands yet: whatever the client sends is echoed back as a bulk string.
 */

public class Server {
    private final ServerSocket serverSocket;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
            r -> new Thread(r, "command-executor")
    );
    private final Commands commands;


    public Server(int port) throws IOException {
        this.serverSocket = new ServerSocket(port);
        this.commands = new Commands(new Store());
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
                final List<String> cmd = command;                       // lamdas need an effectively final variable
                Reply reply = executor.submit(() -> commands.execute(cmd)).get();
                Resp.write(out, reply);
                out.flush();
            }
        } catch (IOException | ExecutionException e){
            // client went away or sent garbage: drop the connection
        } catch (InterruptedException e){
            Thread.currentThread().interrupt();
        }
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]): 7379;
        Server server = new Server(port);
        server.start();
        System.out.println("minidis listening on "+ port);
        Thread.currentThread().join();                            // keep main alive; the acceptor is daemon thread
    }
}
