import java.util.List;

public class PeerInfo {
    private final String peerId;
    private final String host;
    private final int port;
    private final List<String> files;

    public PeerInfo(String peerId, String host, int port, List<String> files) {
        this.peerId = peerId;
        this.host = host;
        this.port = port;
        this.files = files;
    }

    public String getPeerId() { return peerId; }
    public String getHost()   { return host; }
    public int    getPort()   { return port; }
    public List<String> getFiles() { return files; }
}
