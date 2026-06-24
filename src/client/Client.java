import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class Client {

    private final String trackerHost;
    private final int trackerPort;

    public Client(String trackerHost, int trackerPort) {
        this.trackerHost = trackerHost;
        this.trackerPort = trackerPort;
    }

    /*
     * 向 Tracker 发送 QUERY，请求某个文件所在的 Peer。
     * 如果 Tracker 返回 FOUND，则转换成 PeerInfo；
     * 如果返回 NOT_FOUND，则返回 null。
     */
    public PeerInfo queryTracker(String filename) throws IOException {
        try (Socket socket = new Socket(trackerHost, trackerPort);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter writer = new PrintWriter(
                     new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {

            writer.print(Message.buildQuery(filename));
            writer.flush();

            Message response = Message.parse(reader);
            if (response == null) {
                return null;
            }

            if (Message.FOUND.equals(response.getType())) {
                String host = response.getHeader("host");
                String portText = response.getHeader("port");
                if (host == null || portText == null) {
                    throw new IOException("Malformed FOUND response");
                }

                int port = Integer.parseInt(portText);
                return new PeerInfo(null, host, port, List.of(filename));
            }

            if (Message.NOT_FOUND.equals(response.getType())) {
                return null;
            }

            throw new IOException("Unexpected tracker response: " + response.getType());
        }
    }

    /*
     * 连接到指定 Peer，发送 GET 请求，接收文件正文，并校验 MD5。
     */
    public void downloadFromPeer(PeerInfo peer, String filename, String savePath) throws IOException {
        File destinationFile = new File(savePath);

        try (Socket socket = new Socket(peer.getHost(), peer.getPort());
             BufferedInputStream inputStream = new BufferedInputStream(socket.getInputStream());
             PrintWriter writer = new PrintWriter(
                     new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {

            writer.print(Message.buildGet(filename));
            writer.flush();

            Message response = Message.parse(inputStream);
            if (response == null) {
                throw new IOException("Empty response from peer");
            }

            if (Message.ERROR.equals(response.getType())) {
                String reason = response.getHeader("reason");
                throw new IOException(reason == null ? "peer returned ERROR" : reason);
            }

            if (!Message.OK.equals(response.getType())) {
                throw new IOException("Unexpected peer response: " + response.getType());
            }

            String sizeText = response.getHeader("size");
            String md5Text = response.getHeader("md5");
            if (sizeText == null || md5Text == null) {
                throw new IOException("Malformed OK response");
            }

            long size = Long.parseLong(sizeText);
            FileTransfer.receiveFile(inputStream, size, destinationFile);

            String localMd5 = FileTransfer.md5(destinationFile);
            if (!md5Text.equals(localMd5)) {
                throw new IOException("MD5 mismatch after download");
            }
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 4) {
            System.err.println("用法: java Client <tracker_host> <tracker_port> <filename> <save_path>");
            System.exit(1);
        }

        String trackerHost = args[0];
        int trackerPort = Integer.parseInt(args[1]);
        String filename = args[2];
        String savePath = args[3];

        Client client = new Client(trackerHost, trackerPort);
        PeerInfo peer = client.queryTracker(filename);
        if (peer == null) {
            System.out.println("File not found");
            return;
        }

        client.downloadFromPeer(peer, filename, savePath);
        System.out.println("Download complete");
    }
}
