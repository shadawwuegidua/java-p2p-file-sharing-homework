import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class TrackerConnectionHandler implements Runnable {

    private final Socket socket;
    // 同一个文件可以被多个 Peer 持有；后注册的 Peer 会覆盖先注册的 Peer。
    private final Map<String, PeerInfo> registry;

    public TrackerConnectionHandler(Socket socket, Map<String, PeerInfo> registry) {
        this.socket = socket;
        this.registry = registry;
    }

    @Override
    public void run() {
        try (Socket currentSocket = socket;
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(currentSocket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter writer = new PrintWriter(
                     new OutputStreamWriter(currentSocket.getOutputStream(), StandardCharsets.UTF_8), true)) {

            Message message = Message.parse(reader);
            if (message == null) {
                return;
            }

            if (Message.REGISTER.equals(message.getType())) {
                handleRegister(message, writer);
            } else if (Message.QUERY.equals(message.getType())) {
                handleQuery(message, writer);
            } else {
                writer.print(Message.buildError("unsupported message type"));
                writer.flush();
            }
        } catch (IOException ignored) {
            // 单个连接出错不应影响整个 Tracker 服务。
        }
    }

    /*
     * 将 REGISTER 消息中的 peer_id、host、port、files 解析出来，
     * 构造 PeerInfo，并把该 Peer 持有的每个文件都登记到 registry。
     */
    private void handleRegister(Message message, PrintWriter writer) {
        String peerId = message.getHeader("peer_id");
        String host = message.getHeader("host");
        String portText = message.getHeader("port");
        String filesText = message.getHeader("files");

        if (peerId == null || host == null || portText == null) {
            writer.print(Message.buildError("missing register fields"));
            writer.flush();
            return;
        }

        final int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            writer.print(Message.buildError("invalid port"));
            writer.flush();
            return;
        }

        List<String> files = new ArrayList<>();
        if (filesText != null && !filesText.isBlank()) {
            for (String fileName : filesText.split(",")) {
                String trimmedFileName = fileName.trim();
                if (!trimmedFileName.isEmpty()) {
                    files.add(trimmedFileName);
                }
            }
        }

        PeerInfo peerInfo = new PeerInfo(peerId, host, port, List.copyOf(files));
        for (String fileName : files) {
            registry.put(fileName, peerInfo);
        }

        writer.print("OK\n\n");
        writer.flush();
    }

    /*
     * 在 registry 中查找 filename。
     * 找到则返回 FOUND，否则返回 NOT_FOUND。
     */
    private void handleQuery(Message message, PrintWriter writer) {
        String filename = message.getHeader("filename");
        PeerInfo peerInfo = filename == null ? null : registry.get(filename);

        if (peerInfo == null) {
            writer.print(Message.buildNotFound());
        } else {
            writer.print(Message.buildFound(peerInfo.getHost(), peerInfo.getPort()));
        }
        writer.flush();
    }
}
