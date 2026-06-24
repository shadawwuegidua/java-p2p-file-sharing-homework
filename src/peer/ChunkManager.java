import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class ChunkManager {

    public static final int DEFAULT_CHUNK_SIZE = 4 * 1024 * 1024; // 4 MB

    // 将长度为 fileSize 的文件切成若干连续块。
    public static List<FileChunk> divide(long fileSize, int chunkSize) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive");
        }

        List<FileChunk> chunks = new ArrayList<>();
        if (fileSize <= 0) {
            return chunks;
        }

        int index = 0;
        long offset = 0L;
        while (offset < fileSize) {
            long size = Math.min((long) chunkSize, fileSize - offset);
            chunks.add(new FileChunk(index++, offset, size));
            offset += size;
        }

        return chunks;
    }

    // 按 offset 顺序把 chunks 拼回到 dest。
    public static void assemble(List<FileChunk> chunks, File dest) throws IOException {
        File parent = dest.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }

        List<FileChunk> sorted = new ArrayList<>(chunks);
        sorted.sort(Comparator.comparingLong(FileChunk::getOffset));

        try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(dest, false))) {
            for (FileChunk chunk : sorted) {
                byte[] data = chunk.getData();
                if (data == null) {
                    throw new IOException("Chunk data is missing");
                }
                out.write(data);
            }
            out.flush();
        }
    }
}
