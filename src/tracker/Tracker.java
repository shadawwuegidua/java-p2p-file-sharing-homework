import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Tracker {

    private final int port;
    private final ExecutorService threadPool;
    // registry 维护 filename -> PeerInfo 的映射；ConcurrentHashMap 适合多线程读写。
    private final Map<String, PeerInfo> registry;

    public Tracker(int port) {
        this.port = port;
        this.threadPool = Executors.newCachedThreadPool();
        this.registry = new ConcurrentHashMap<>();
    }

    // 监听端口并不断接收连接，把每个连接交给线程池处理。
    public void start() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(port, 256)) {
            while (true) {
                Socket socket = serverSocket.accept();
                threadPool.execute(new TrackerConnectionHandler(socket, registry));
            }
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("用法: java Tracker <port>");
            System.exit(1);
        }

        int port = Integer.parseInt(args[0]);
        new Tracker(port).start();
    }
}
