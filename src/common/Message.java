import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;

public class Message {

    public static final String REGISTER  = "REGISTER";
    public static final String QUERY     = "QUERY";
    public static final String FOUND     = "FOUND";
    public static final String NOT_FOUND = "NOT_FOUND";
    public static final String GET       = "GET";
    public static final String OK        = "OK";
    public static final String ERROR     = "ERROR";

    private String type;
    private Map<String, String> headers;

    // 从 reader 逐行读取直到空行；第一行为消息类型，后续行是 "key: value"
    public static Message parse(BufferedReader reader) throws IOException {
        String firstLine = reader.readLine();
        if (firstLine == null) {
            return null;
        }
        if (firstLine.isEmpty()) {
            throw new IOException("Empty message type");
        }

        Message message = new Message();
        message.type = firstLine;
        message.headers = new HashMap<>();

        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                break;
            }

            // 按第一个冒号切分，匹配协议里的 "key: value" 格式
            int colonIndex = line.indexOf(':');
            if (colonIndex < 0) {
                throw new IOException("Invalid header line: " + line);
            }

            String key = line.substring(0, colonIndex).trim();
            String value = line.substring(colonIndex + 1).trim();
            if (!key.isEmpty()) {
                message.headers.put(key, value);
            }
        }

        return message;
    }

    /*
     * 从原始 InputStream 中读取一条消息头。
     * 这个版本不会像 BufferedReader 那样预读更多正文数据，
     * 因此适合在读取完消息头后立刻继续读取文件字节流的场景。
     */
    public static Message parse(InputStream inputStream) throws IOException {
        String firstLine = readLine(inputStream);
        if (firstLine == null) {
            return null;
        }
        if (firstLine.isEmpty()) {
            throw new IOException("Empty message type");
        }

        Message message = new Message();
        message.type = firstLine;
        message.headers = new HashMap<>();

        String line;
        while ((line = readLine(inputStream)) != null) {
            if (line.isEmpty()) {
                break;
            }

            int colonIndex = line.indexOf(':');
            if (colonIndex < 0) {
                throw new IOException("Invalid header line: " + line);
            }

            String key = line.substring(0, colonIndex).trim();
            String value = line.substring(colonIndex + 1).trim();
            if (!key.isEmpty()) {
                message.headers.put(key, value);
            }
        }

        return message;
    }

    /*
     * 逐字节读取一行 UTF-8 文本，支持 \n 和 \r\n。
     * 返回值不包含换行符；如果流已经结束且没有读到任何内容，则返回 null。
     */
    private static String readLine(InputStream inputStream) throws IOException {
        ByteArrayOutputStream lineBuffer = new ByteArrayOutputStream();
        boolean sawAnyByte = false;

        while (true) {
            int nextByte = inputStream.read();
            if (nextByte == -1) {
                if (!sawAnyByte) {
                    return null;
                }
                break;
            }

            sawAnyByte = true;

            if (nextByte == '\n') {
                break;
            }

            if (nextByte != '\r') {
                lineBuffer.write(nextByte);
            }
        }

        return lineBuffer.toString(StandardCharsets.UTF_8);
    }

    public String getType() {
        return type;
    }

    public String getHeader(String key) {
        if (headers == null) {
            return null;
        }
        return headers.get(key);
    }

    // 统一追加 "key: value\n"
    private static void appendHeader(StringBuilder sb, String key, String value) {
        sb.append(key).append(": ").append(value).append('\n');
    }

    // 构造 REGISTER
    public static String buildRegister(String peerId, String host, int port, List<String> files) {
        StringBuilder sb = new StringBuilder();
        sb.append(REGISTER).append('\n');
        appendHeader(sb, "peer_id", peerId);
        appendHeader(sb, "host", host);
        appendHeader(sb, "port", String.valueOf(port));
        appendHeader(sb, "files", String.join(",", files));
        sb.append('\n');
        return sb.toString();
    }

    // 构造 QUERY
    public static String buildQuery(String filename) {
        StringBuilder sb = new StringBuilder();
        sb.append(QUERY).append('\n');
        appendHeader(sb, "filename", filename);
        sb.append('\n');
        return sb.toString();
    }

    // 构造 FOUND
    public static String buildFound(String host, int port) {
        StringBuilder sb = new StringBuilder();
        sb.append(FOUND).append('\n');
        appendHeader(sb, "host", host);
        appendHeader(sb, "port", String.valueOf(port));
        sb.append('\n');
        return sb.toString();
    }

    // 构造 NOT_FOUND
    public static String buildNotFound() {
        StringBuilder sb = new StringBuilder();
        sb.append(NOT_FOUND).append('\n');
        sb.append('\n');
        return sb.toString();
    }

    // 构造 GET
    public static String buildGet(String filename) {
        StringBuilder sb = new StringBuilder();
        sb.append(GET).append('\n');
        appendHeader(sb, "filename", filename);
        sb.append('\n');
        return sb.toString();
    }

    // 构造带 offset/length 的 GET
    public static String buildGet(String filename, long offset, long length) {
        StringBuilder sb = new StringBuilder();
        sb.append(GET).append('\n');
        appendHeader(sb, "filename", filename);
        appendHeader(sb, "offset", String.valueOf(offset));
        appendHeader(sb, "length", String.valueOf(length));
        sb.append('\n');
        return sb.toString();
    }

    // 构造 OK 响应头；文件字节流需要在外部单独写出
    public static String buildOk(String filename, long size, String md5) {
        StringBuilder sb = new StringBuilder();
        sb.append(OK).append('\n');
        appendHeader(sb, "filename", filename);
        appendHeader(sb, "size", String.valueOf(size));
        appendHeader(sb, "md5", md5);
        sb.append('\n');
        return sb.toString();
    }

    // 构造 ERROR
    public static String buildError(String reason) {
        StringBuilder sb = new StringBuilder();
        sb.append(ERROR).append('\n');
        appendHeader(sb, "reason", reason);
        sb.append('\n');
        return sb.toString();
    }
}
