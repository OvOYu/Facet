import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Raw-bit round trips and a brute-force reference for small planning instances. */
public final class Correctness {
  static long checks,packets,brute;
  static long bits(double x){return Double.doubleToRawLongBits(x);}
  static int width(long x){return 64-Long.numberOfLeadingZeros(x);}
  static long zz(long x){return (x<<1)^(x>>63);}
  static long key(long x){return x<0?~x:x^Long.MIN_VALUE;}
  static long groupCost(long[] x,int start,int end,boolean seed){
    long best=Long.MAX_VALUE;int begin=start+(seed?1:0),n=end-begin;
    for(int predictor=0;predictor<3;predictor++){
      long[] codes=new long[n];long mask=0;
      for(int i=begin;i<end;i++){
        long d=x[i]-x[i-1];long previous=(seed&&i==start+1)?0:x[i-1]-x[i-2];
        long code=predictor==0?x[i]^x[i-1]:zz(predictor==1?d:d-previous);
        codes[i-begin]=code;mask|=code;
      }
      int t=mask==0?0:Long.numberOfTrailingZeros(mask),maximum=0;
      for(long code:codes)maximum=Math.max(maximum,width(code>>>t));
      for(int w=0;w<=maximum;w++){
        int patches=0;for(long code:codes)if(width(code>>>t)>w)patches++;
        long cost=(seed?64:0)+FacetCodec.GROUP_HEADER_BITS+(long)n*w+
                  patches*(FacetCodec.POSITION_BITS+(long)maximum-w);
        best=Math.min(best,cost);
      }
    }
    return best;
  }
  static int[] scales(double[] x){
    Integer[] order=new Integer[17];long[] score=new long[17];
    for(int scale=0;scale<=16;scale++){
      order[scale]=scale;double power=Double.parseDouble("1e"+scale);long previous=0;
      for(int i=0;i<Math.min(FacetCodec.SAMPLE,x.length);i++){
        double s=x[i]*power;long q=Math.round(s);
        boolean exact=Double.isFinite(x[i])&&Math.abs(s)<0x1.0p62&&bits(q/power)==bits(x[i]);
        long v=exact?q:0;score[scale]+=exact?width(zz(v-previous)):65;previous=v;
      }
    }
    Arrays.sort(order,(a,b)->score[a]==score[b]?Integer.compare(a,b):Long.compare(score[a],score[b]));
    int[] result=new int[FacetCodec.CANDIDATES];for(int i=0;i<result.length;i++)result[i]=order[i];return result;
  }
  static long segmentCost(double[] values,int start,int end,int kind,int scale){
    long header=2+Math.max(10,32-Integer.numberOfLeadingZeros(Math.max(1,values.length)-1));
    if(kind==0)return header+64L*(end-start);
    long[] x=new long[end];int exceptions=0;double power=Double.parseDouble("1e"+scale);
    for(int i=start;i<end;i++){
      if(kind==2)x[i]=key(bits(values[i]));
      else{
        double scaled=values[i]*power;long q=Math.round(scaled);
        boolean exact=Double.isFinite(values[i])&&Math.abs(scaled)<0x1.0p62&&bits(q/power)==bits(values[i]);
        x[i]=exact?q:0;if(!exact)exceptions++;
      }
    }
    if(kind==1)header+=6+(exceptions>0?end-start+64L*exceptions:0);
    for(int s=start;s<end;s+=FacetCodec.GROUP)
      header+=groupCost(x,s,Math.min(end,s+FacetCodec.GROUP),s==start);
    return header;
  }
  static long enumerate(double[] x,int start,int segments,int[] scales){
    if(start==x.length)return 0;
    if(segments==FacetCodec.MAX_SEGMENTS)return Long.MAX_VALUE/4;
    long best=Long.MAX_VALUE;
    for(int end=Math.min(x.length,start+FacetCodec.GROUP);;end=Math.min(x.length,end+FacetCodec.GROUP)){
      long rest=enumerate(x,end,segments+1,scales);
      best=Math.min(best,segmentCost(x,start,end,0,0)+rest);
      best=Math.min(best,segmentCost(x,start,end,2,0)+rest);
      for(int scale:scales)best=Math.min(best,segmentCost(x,start,end,1,scale)+rest);
      if(end==x.length)break;
    }
    return best;
  }
  static void reference(double[] x){
    if(x.length==0)return;
    int[] selected=scales(x);
    long expected=29+enumerate(x,0,0,selected);
    long raw=24+64L*x.length;
    if((expected+7)/8>=(raw+7)/8)expected=raw;
    if(new FacetCodec().plan(x).bits!=expected)throw new AssertionError("brute-force planning cost");
    brute++;
  }
  static void test(double[] x,Path directory,int id)throws Exception{
    ByteBuffer raw=ByteBuffer.allocate(x.length*8).order(ByteOrder.LITTLE_ENDIAN);
    for(double v:x)raw.putLong(bits(v));
    Files.write(directory.resolve("case-"+id+".input"),raw.array(),StandardOpenOption.CREATE_NEW);
    FacetCodec codec=new FacetCodec();
    byte[] packet=codec.encode(x);
    double[] restored=codec.decode(packet);
    if(x.length!=restored.length)throw new AssertionError("length mismatch");
    for(int i=0;i<x.length;i++)
      if(bits(x[i])!=bits(restored[i]))throw new AssertionError("raw-bit mismatch at "+i);
    if(packet.length>3+8*x.length)throw new AssertionError("raw storage bound");
    Files.write(directory.resolve("case-"+id+".packet"),packet,StandardOpenOption.CREATE_NEW);
    packets++;
    checks+=x.length;
  }
  public static void main(String[] args)throws Exception{
    if(args.length!=1)throw new IllegalArgumentException("new packet directory required");
    Path directory=Paths.get(args[0]);Files.createDirectory(directory);
    Random r=new Random(73421);int id=0;
    long[] edge={0L,Long.MIN_VALUE,0x7ff0000000000000L,0xfff0000000000000L,
                 0x7ff8000000000000L,0x7ff0000000000001L,0xfff8000000000011L,
                 1L,Long.MIN_VALUE|1L,0x0010000000000000L,0x7fefffffffffffffL};
    double[] x=new double[edge.length];for(int i=0;i<x.length;i++)x[i]=Double.longBitsToDouble(edge[i]);
    test(x,directory,id++);test(new double[0],directory,id++);
    for(int n:new int[]{1,2,31,32,33,63,64,65,127,128,129,255,256,257,511,512,513,999,1000,1001,1023,1024,1025,2000,4000,8000}){
      for(int kind=0;kind<3;kind++){
        x=new double[n];for(int i=0;i<n;i++)x[i]=kind==0?Double.longBitsToDouble(r.nextLong()):kind==1?(r.nextInt(20001)-10000)/100.0:i%67==0?Double.longBitsToDouble(r.nextLong()):i*i/1000.0;
        test(x,directory,id++);
      }
    }
    for(int k=0;k<80;k++){
      int n=1+r.nextInt(257);x=new double[n];
      for(int i=0;i<n;i++)x[i]=k%4==0?Double.longBitsToDouble(r.nextLong()):k%4==1?(r.nextInt(2001)-1000)/10.0:k%4==2?0:i/100.0;
      test(x,directory,id++);if(k<32)reference(x);
    }
    System.out.printf(Locale.ROOT,"{\"cases\":%d,\"packets\":%d,\"raw_bit_comparisons\":%d,\"brute_force_plans\":%d,\"mismatches\":0}%n",id,packets,checks,brute);
  }
}
