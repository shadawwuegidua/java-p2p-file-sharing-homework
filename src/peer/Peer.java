import java.io.File;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Peer {

    private final String trackerHost;
    private final int trackerPort;
    private final int peerPort;
    private final String fileDir;
    private final ExecutorService threadPool;

    public Peer(String trackerHost, int trackerPort, int peerPort, String fileDir) {
        this.trackerHost = trackerHost;
        this.trackerPort = trackerPort;
        this.peerPort = peerPort;
        this.fileDir = fileDir;
        this.threadPool = Executors.newCachedThreadPool();
    }

    /*
     * 扫描本地目录中的文件，然后向 Tracker 发送 REGISTER 消息。
     * 注册的文件列表用于后续 QUERY 时建立 filename -> PeerInfo 的索引。
     */
    public void registerToTracker() throws IOException {
        File directory = new File(fileDir);
        List<String> fileNames = new ArrayList<>();

        File[] files = directory.listFiles(File::isFile);
        if (files != null) {
            Arrays.stream(files)
                    .sorted(Comparator.comparing(File::getName))
                    .forEach(file -> fileNames.add(file.getName()));
        }

        String peerId = trackerHost + ":" + peerPort;
        String registerMessage = Message.buildRegister(peerId, trackerHost, peerPort, fileNames);

        try (Socket socket = new Socket(trackerHost, trackerPort);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter writer = new PrintWriter(
                     new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {
            socket.setTcpNoDelay(true);

            writer.print(registerMessage);
            writer.flush();

            Message acknowledgement = Message.parse(reader);
            if (acknowledgement != null && !Message.OK.equals(acknowledgement.getType())) {
                throw new IOException("Unexpected tracker response: " + acknowledgement.getType());
            }
        }
    }

    /*
     * 在 peerPort 上监听客户端连接。
     * 每个连接交给线程池中的一个 PeerConnectionHandler 处理。
     */
    public void startServer() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(peerPort, 256)) {
            while (true) {
                Socket socket = serverSocket.accept();
                threadPool.execute(new PeerConnectionHandler(socket, fileDir));
            }
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 4) {
            System.err.println("用法: java Peer <tracker_host> <tracker_port> <peer_port> <file_dir>");
            System.exit(1);
        }

        String trackerHost = args[0];
        int trackerPort = Integer.parseInt(args[1]);
        int peerPort = Integer.parseInt(args[2]);
        String fileDir = args[3];

        Peer peer = new Peer(trackerHost, trackerPort, peerPort, fileDir);
        peer.registerToTracker();
        peer.startServer();
    }
}
