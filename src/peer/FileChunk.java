public class FileChunk {

    private final int index;
    private final long offset;
    private final long size;
    private byte[] data;

    public FileChunk(int index, long offset, long size) {
        this.index = index;
        this.offset = offset;
        this.size = size;
    }

    public int     getIndex()  { return index; }
    public long    getOffset() { return offset; }
    public long    getSize()   { return size; }
    public byte[]  getData()   { return data; }

    public void setData(byte[] data) {
        this.data = data;
    }
}
