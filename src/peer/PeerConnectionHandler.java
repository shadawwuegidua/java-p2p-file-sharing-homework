import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PeerConnectionHandler implements Runnable {

    private static final int BUFFER_SIZE = 64 * 1024;

    /*
     * 文件元数据缓存：同一个文件被重复请求时，不必每次都重新计算 MD5。
     * 缓存键由文件绝对路径、大小和最后修改时间组成。
     */
    private static final Map<String, CachedMetadata> METADATA_CACHE = new ConcurrentHashMap<>();

    private final Socket socket;
    private final String fileDir;

    public PeerConnectionHandler(Socket socket, String fileDir) {
        this.socket = socket;
        this.fileDir = fileDir;
    }

    @Override
    public void run() {
        try (Socket currentSocket = socket;
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(currentSocket.getInputStream(), StandardCharsets.UTF_8));
             OutputStream rawOutputStream = currentSocket.getOutputStream();
             PrintWriter textWriter = new PrintWriter(
                     new OutputStreamWriter(rawOutputStream, StandardCharsets.UTF_8), true)) {

            Message message = Message.parse(reader);
            if (message == null) {
                return;
            }

            if (Message.GET.equals(message.getType())) {
                handleGet(message, textWriter, rawOutputStream);
            } else {
                textWriter.print(Message.buildError("unsupported message type"));
                textWriter.flush();
            }
        } catch (IOException ignored) {
            // 单个连接出错不应影响整个 Peer 服务。
        }
    }

    /*
     * 处理单个 GET 请求：
     * 1. 检查文件是否存在
     * 2. 如果请求带 offset/length，则只发送指定范围
     * 3. 先写 OK 头，再写文件字节流
     */
    private void handleGet(Message message, PrintWriter textWriter, OutputStream rawOutputStream) throws IOException {
        String filename = message.getHeader("filename");
        if (filename == null || filename.isBlank()) {
            textWriter.print(Message.buildError("missing filename"));
            textWriter.flush();
            return;
        }

        File file = new File(fileDir, filename);
        if (!file.isFile()) {
            textWriter.print(Message.buildError("file not found"));
            textWriter.flush();
            return;
        }

        long fileSize = file.length();
        CachedMetadata metadata = metadataFor(file);

        String offsetText = message.getHeader("offset");
        String lengthText = message.getHeader("length");

        long offset = 0L;
        long length = fileSize;
        boolean isRangeRequest = offsetText != null || lengthText != null;

        if (isRangeRequest) {
            if (offsetText == null || lengthText == null) {
                textWriter.print(Message.buildError("invalid range request"));
                textWriter.flush();
                return;
            }

            try {
                offset = Long.parseLong(offsetText);
                length = Long.parseLong(lengthText);
            } catch (NumberFormatException e) {
                textWriter.print(Message.buildError("invalid range request"));
                textWriter.flush();
                return;
            }

            if (offset < 0 || length < 0 || offset > fileSize || length > fileSize - offset) {
                textWriter.print(Message.buildError("invalid range request"));
                textWriter.flush();
                return;
            }
        }

        textWriter.print(Message.buildOk(filename, fileSize, metadata.md5));
        textWriter.flush();

        if (length == 0) {
            return;
        }

        sendRange(rawOutputStream, file, offset, length);
    }

    /*
     * 从文件的指定位置开始发送固定长度的字节。
     * 这里用 RandomAccessFile 直接跳到 offset，避免读取不需要的部分。
     */
    private void sendRange(OutputStream outputStream, File file, long offset, long length) throws IOException {
        try (RandomAccessFile randomAccessFile = new RandomAccessFile(file, "r")) {
            randomAccessFile.seek(offset);

            byte[] buffer = new byte[BUFFER_SIZE];
            long bytesRemaining = length;

            while (bytesRemaining > 0) {
                int bytesToRead = (int) Math.min(buffer.length, bytesRemaining);
                int bytesRead = randomAccessFile.read(buffer, 0, bytesToRead);
                if (bytesRead == -1) {
                    throw new IOException("Unexpected end of file while sending range");
                }

                outputStream.write(buffer, 0, bytesRead);
                bytesRemaining -= bytesRead;
            }

            outputStream.flush();
        }
    }

    /*
     * 读取或生成文件元数据。
     * 缓存 MD5 可以避免同一个大文件被重复请求时反复计算摘要。
     */
    private CachedMetadata metadataFor(File file) throws IOException {
        String cacheKey = file.getAbsolutePath() + "|" + file.length() + "|" + file.lastModified();
        CachedMetadata cachedMetadata = METADATA_CACHE.get(cacheKey);
        if (cachedMetadata != null) {
            return cachedMetadata;
        }

        CachedMetadata createdMetadata = new CachedMetadata(file.length(), FileTransfer.md5(file));
        METADATA_CACHE.put(cacheKey, createdMetadata);
        return createdMetadata;
    }

    private static final class CachedMetadata {
        private final long size;
        private final String md5;

        private CachedMetadata(long size, String md5) {
            this.size = size;
            this.md5 = md5;
        }
    }
}
