import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class ParallelDownloader {

    private final List<PeerInfo> peers;
    private final String filename;
    private final File destinationFile;

    public ParallelDownloader(List<PeerInfo> peers, String filename, File destinationFile) {
        this.peers = peers;
        this.filename = filename;
        this.destinationFile = destinationFile;
    }

    /*
     * 先探测文件大小和 MD5，再按块并行下载，最后校验整体 MD5。
     */
    public void download() throws IOException, InterruptedException {
        if (peers == null || peers.isEmpty()) {
            throw new IOException("No peers available");
        }

        FileMetadata metadata = probeMetadata();
        if (metadata.size == 0) {
            File parentDirectory = destinationFile.getParentFile();
            if (parentDirectory != null) {
                parentDirectory.mkdirs();
            }
            try (RandomAccessFile randomAccessFile = new RandomAccessFile(destinationFile, "rw")) {
                randomAccessFile.setLength(0);
            }
            return;
        }

        List<FileChunk> chunks = ChunkManager.divide(metadata.size, ChunkManager.DEFAULT_CHUNK_SIZE);
        long targetChunkCount = Math.max(4L, (long) peers.size() * 4L);
        int adaptiveChunkSize = (int) Math.max(256L * 1024L,
                Math.min((long) ChunkManager.DEFAULT_CHUNK_SIZE, metadata.size / targetChunkCount));
        if (adaptiveChunkSize > 0 && adaptiveChunkSize != ChunkManager.DEFAULT_CHUNK_SIZE) {
            chunks = ChunkManager.divide(metadata.size, adaptiveChunkSize);
        }

        File parentDirectory = destinationFile.getParentFile();
        if (parentDirectory != null) {
            parentDirectory.mkdirs();
        }
        try (RandomAccessFile randomAccessFile = new RandomAccessFile(destinationFile, "rw")) {
            randomAccessFile.setLength(metadata.size);
        }

        int workerCount = Math.min(chunks.size(),
                Math.max(4, Math.max(peers.size() * 4, Runtime.getRuntime().availableProcessors() * 2)));
        ExecutorService executorService = Executors.newFixedThreadPool(workerCount);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            final int chunkIndex = i;
            final FileChunk chunk = chunks.get(i);
            futures.add(executorService.submit(() -> {
                downloadChunkWithRetries(chunkIndex, chunk, metadata.size);
                return null;
            }));
        }

        executorService.shutdown();

        try {
            for (Future<?> future : futures) {
                future.get();
            }
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException("Parallel download failed", cause);
        }

        String localMd5 = FileTransfer.md5(destinationFile);
        if (!metadata.md5.equals(localMd5)) {
            throw new IOException("MD5 mismatch after parallel download");
        }
    }

    /*
     * 从任意一个 Peer 探测文件元数据。
     * 这里必须从原始 InputStream 解析消息头，避免 BufferedReader 预读掉正文字节。
     */
    private FileMetadata probeMetadata() throws IOException {
        IOException lastError = null;

        for (PeerInfo peer : peers) {
            try (Socket socket = new Socket(peer.getHost(), peer.getPort());
                 BufferedInputStream inputStream = new BufferedInputStream(socket.getInputStream());
                 PrintWriter writer = new PrintWriter(
                         new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {

                writer.print(Message.buildGet(filename, 0, 0));
                writer.flush();

                Message response = Message.parse(inputStream);
                if (response == null) {
                    throw new IOException("Empty metadata response");
                }

                if (Message.ERROR.equals(response.getType())) {
                    String reason = response.getHeader("reason");
                    throw new IOException(reason == null ? "Peer returned ERROR" : reason);
                }

                if (!Message.OK.equals(response.getType())) {
                    throw new IOException("Unexpected response: " + response.getType());
                }

                String sizeText = response.getHeader("size");
                String md5Text = response.getHeader("md5");
                if (sizeText == null || md5Text == null) {
                    throw new IOException("Malformed OK metadata response");
                }

                return new FileMetadata(Long.parseLong(sizeText), md5Text);
            } catch (IOException exception) {
                lastError = exception;
            }
        }

        throw lastError == null ? new IOException("Unable to probe file metadata") : lastError;
    }

    /*
     * 对每个分块依次尝试多个 Peer，直到成功。
     */
    private void downloadChunkWithRetries(int chunkIndex, FileChunk chunk, long totalSize) throws IOException {
        int peerCount = peers.size();
        IOException lastError = null;

        for (int attempt = 0; attempt < peerCount; attempt++) {
            PeerInfo peer = peers.get((chunkIndex + attempt) % peerCount);
            try {
                downloadChunkFromPeer(peer, chunk, totalSize);
                return;
            } catch (IOException exception) {
                lastError = exception;
            }
        }

        throw lastError == null ? new IOException("Chunk download failed") : lastError;
    }

    /*
     * 下载单个 chunk，并将结果直接写入目标文件对应偏移位置。
     */
    private void downloadChunkFromPeer(PeerInfo peer, FileChunk chunk, long totalSize) throws IOException {
        try (Socket socket = new Socket(peer.getHost(), peer.getPort());
             BufferedInputStream inputStream = new BufferedInputStream(socket.getInputStream());
             PrintWriter writer = new PrintWriter(
                     new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {

            writer.print(Message.buildGet(filename, chunk.getOffset(), chunk.getSize()));
            writer.flush();

            Message response = Message.parse(inputStream);
            if (response == null) {
                throw new IOException("Empty peer response");
            }

            if (Message.ERROR.equals(response.getType())) {
                String reason = response.getHeader("reason");
                throw new IOException(reason == null ? "Peer returned ERROR" : reason);
            }

            if (!Message.OK.equals(response.getType())) {
                throw new IOException("Unexpected peer response: " + response.getType());
            }

            String sizeText = response.getHeader("size");
            if (sizeText == null) {
                throw new IOException("Malformed OK response");
            }

            long reportedSize = Long.parseLong(sizeText);
            if (reportedSize != totalSize) {
                throw new IOException("Peer reported inconsistent file size");
            }

            writeRangeToDestination(inputStream, chunk.getOffset(), chunk.getSize());
        }
    }

    /*
     * 将指定长度的字节写入目标文件的指定偏移位置。
     */
    private void writeRangeToDestination(InputStream inputStream, long offset, long length) throws IOException {
        try (RandomAccessFile randomAccessFile = new RandomAccessFile(destinationFile, "rw")) {
            randomAccessFile.seek(offset);

            byte[] buffer = new byte[64 * 1024];
            long bytesRemaining = length;

            while (bytesRemaining > 0) {
                int bytesToRead = (int) Math.min(buffer.length, bytesRemaining);
                int bytesRead = inputStream.read(buffer, 0, bytesToRead);
                if (bytesRead == -1) {
                    throw new IOException("Unexpected end of stream while downloading chunk");
                }
                randomAccessFile.write(buffer, 0, bytesRead);
                bytesRemaining -= bytesRead;
            }
        }
    }

    private static final class FileMetadata {
        private final long size;
        private final String md5;

        private FileMetadata(long size, String md5) {
            this.size = size;
            this.md5 = md5;
        }
    }
}
