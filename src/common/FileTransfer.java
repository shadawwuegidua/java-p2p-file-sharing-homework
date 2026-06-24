import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class FileTransfer {

    /*
     * 缓冲区大小决定每次搬运多少字节。
     * 这里使用 64KB，通常能在吞吐量和内存占用之间取得比较平衡的效果。
     */
    private static final int BUFFER_SIZE = 64 * 1024;

    /*
     * 将本地文件的所有字节写入输出流。
     * 这里写入的是原始二进制字节，不涉及文本协议。
     * 调用方通常会先写消息头，再调用这个方法写文件正文。
     */
    public static void sendFile(OutputStream outputStream, File file) throws IOException {
        try (InputStream fileInputStream = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;

            while ((bytesRead = fileInputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }

            outputStream.flush();
        }
    }

    /*
     * 从输入流中精确读取 size 个字节，并写入目标文件。
     * 如果输入流提前结束，说明对端传输中断或协议错误，必须抛出异常。
     */
    public static void receiveFile(InputStream inputStream, long size, File destinationFile) throws IOException {
        if (size < 0) {
            throw new IOException("Negative file size");
        }

        File parentDirectory = destinationFile.getParentFile();
        if (parentDirectory != null) {
            parentDirectory.mkdirs();
        }

        try (OutputStream fileOutputStream = new BufferedOutputStream(new FileOutputStream(destinationFile, false))) {
            byte[] buffer = new byte[BUFFER_SIZE];
            long bytesRemaining = size;

            while (bytesRemaining > 0) {
                int bytesToRead = (int) Math.min(buffer.length, bytesRemaining);
                int bytesRead = inputStream.read(buffer, 0, bytesToRead);

                if (bytesRead == -1) {
                    throw new IOException("Unexpected end of stream while receiving file");
                }

                fileOutputStream.write(buffer, 0, bytesRead);
                bytesRemaining -= bytesRead;
            }

            fileOutputStream.flush();
        }
    }

    /*
     * 计算文件的 MD5 摘要，返回 32 位小写十六进制字符串。
     * 这个值用于下载完成后的完整性校验。
     */
    public static String md5(File file) throws IOException {
        final MessageDigest messageDigest;
        try {
            messageDigest = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("MD5 algorithm not available", e);
        }

        try (InputStream fileInputStream = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;

            while ((bytesRead = fileInputStream.read(buffer)) != -1) {
                messageDigest.update(buffer, 0, bytesRead);
            }
        }

        byte[] hashBytes = messageDigest.digest();
        StringBuilder hexadecimalDigest = new StringBuilder(hashBytes.length * 2);

        for (byte hashByte : hashBytes) {
            int unsignedValue = hashByte & 0xff;
            if (unsignedValue < 0x10) {
                hexadecimalDigest.append('0');
            }
            hexadecimalDigest.append(Integer.toHexString(unsignedValue));
        }

        return hexadecimalDigest.toString();
    }
}
