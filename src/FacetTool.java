import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Binary64 file interface. Output paths must be new files. */
public final class FacetTool {
  static final long MAGIC=0x4641434554303031L;
  static final int BLOCK_SIZE = 1000;

  private static double[] loadText(Path input) throws IOException {
    List<Double> values = new ArrayList<>();
    try (BufferedReader reader = Files.newBufferedReader(input)) {
      String line;
      boolean first = true;
      while ((line = reader.readLine()) != null) {
        if (first && line.startsWith("\uFEFF")) line = line.substring(1);
        first = false;
        String value = line.trim();
        if (!value.isEmpty() && !value.equals("\"\"")) values.add(Double.parseDouble(value));
      }
    }
    double[] result = new double[values.size()];
    for (int i = 0; i < result.length; i++) result[i] = values.get(i);
    return result;
  }

  private static void demo(Path input) throws IOException {
    double[] values = input.getFileName().toString().endsWith(".csv") ? loadText(input) : load(input);
    FacetCodec codec = new FacetCodec();
    List<byte[]> packets = new ArrayList<>();
    long packetBytes = 0;
    long start = System.nanoTime();
    for (int i = 0; i < values.length; i += BLOCK_SIZE) {
      byte[] packet = codec.encode(Arrays.copyOfRange(values, i, Math.min(values.length, i + BLOCK_SIZE)));
      packets.add(packet);
      packetBytes += packet.length;
    }
    long encodeTime = System.nanoTime() - start;
    start = System.nanoTime();
    List<double[]> restored = new ArrayList<>();
    for (byte[] packet : packets) restored.add(codec.decode(packet));
    long decodeTime = System.nanoTime() - start;
    int compared = 0;
    for (double[] block : restored) {
      for (double value : block) {
        if (compared >= values.length ||
            Double.doubleToRawLongBits(value) != Double.doubleToRawLongBits(values[compared]))
          throw new AssertionError("raw-bit mismatch at " + compared);
        compared++;
      }
    }
    if (compared != values.length) throw new AssertionError("decoded count mismatch");
    System.out.printf(Locale.ROOT,
        "values=%d blocks=%d packet_bytes=%d restored_values=%d encode_ms=%.3f decode_ms=%.3f%n",
        values.length, packets.size(), packetBytes, compared, encodeTime / 1e6, decodeTime / 1e6);
  }

  static double[] load(Path input)throws IOException{
    byte[] raw=Files.readAllBytes(input);
    if(raw.length%8!=0)throw new IllegalArgumentException("input must contain little-endian binary64 values");
    ByteBuffer b=ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
    double[] values=new double[raw.length/8];
    for(int i=0;i<values.length;i++)values[i]=Double.longBitsToDouble(b.getLong());
    return values;
  }
  public static void main(String[] args)throws Exception{
    if (args.length == 2 && args[0].equals("demo")) {
      demo(Paths.get(args[1]));
      return;
    }
    if (args.length != 3)
      throw new IllegalArgumentException("usage: demo INPUT | encode INPUT OUTPUT | decode INPUT OUTPUT");
    if(args[0].equals("encode")){
      FacetCodec c=new FacetCodec();
      double[] values=load(Paths.get(args[1]));
      try(DataOutputStream out=new DataOutputStream(Files.newOutputStream(Paths.get(args[2]),StandardOpenOption.CREATE_NEW))){
        out.writeLong(MAGIC);out.writeLong(values.length);
        for(int i=0;i<values.length;i+=BLOCK_SIZE){
          byte[] packet=c.encode(Arrays.copyOfRange(values,i,Math.min(values.length,i+BLOCK_SIZE)));
          out.writeInt(packet.length);out.write(packet);
        }
      }
    }else if(args[0].equals("decode")){
      FacetCodec c=new FacetCodec();
      try(DataInputStream in=new DataInputStream(Files.newInputStream(Paths.get(args[1])));
          OutputStream out=Files.newOutputStream(Paths.get(args[2]),StandardOpenOption.CREATE_NEW)){
        if(in.readLong()!=MAGIC)throw new IllegalArgumentException("container signature");
        long remaining=in.readLong();if(remaining<0)throw new IllegalArgumentException("negative value count");
        while(remaining>0){
          int length=in.readInt();if(length<3||length>8*65535+3)throw new IllegalArgumentException("packet length");
          byte[] packet=new byte[length];in.readFully(packet);double[] values=c.decode(packet);
          if(values.length==0||values.length>remaining)throw new IllegalArgumentException("container value count");
          ByteBuffer raw=ByteBuffer.allocate(values.length*8).order(ByteOrder.LITTLE_ENDIAN);
          for(double v:values)raw.putLong(Double.doubleToRawLongBits(v));
          out.write(raw.array());remaining-=values.length;
        }
        if(in.read()!=-1)throw new IllegalArgumentException("trailing container data");
      }
    }else throw new IllegalArgumentException("unknown action");
  }
}
