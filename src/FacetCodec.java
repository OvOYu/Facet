import java.util.*;

/** Segment-wise reversible integer views and a self-contained block format.
 * No dataset-specific choices. Costs below equal the bitstream including headers.
 * NaNs and exceptional decimal slots are represented by their untouched raw bits.
 */
public final class FacetCodec {
  static final int GROUP=64, MAX_SEGMENTS=16;
  static final int CANDIDATES=3, SAMPLE=32;
  static final int POSITION_BITS=6, COUNT_BITS=7;
  static final int GROUP_HEADER_BITS=22+COUNT_BITS;
  static int lengthBits(int n){return Math.max(10,32-Integer.numberOfLeadingZeros(Math.max(1,n)-1));}
  static long bits(double v){return Double.doubleToRawLongBits(v);}
  static long zig(long v){return (v<<1)^(v>>63);}
  static long unzig(long v){return (v>>>1)^-(v&1);}
  static int width(long v){return 64-Long.numberOfLeadingZeros(v);}
  static long key(long v){return v<0?~v:v^Long.MIN_VALUE;}
  static long unkey(long v){return v<0?v^Long.MIN_VALUE:~v;}
  static final double[] POW=new double[17];
  static {for(int i=0;i<POW.length;i++)POW[i]=Double.parseDouble("1e"+i);}

  static final class Writer {
    byte[] bytes=new byte[16384]; int pos;
    void put(long v,int w){
      if(pos+w>bytes.length*8)bytes=Arrays.copyOf(bytes,Math.max(bytes.length*2,(pos+w+7)/8));
      while(w>0){int take=Math.min(8-(pos&7),w),shift=w-take;
        bytes[pos>>>3]|=(byte)(((v>>>shift)&((1<<take)-1))<<(8-(pos&7)-take));pos+=take;w-=take;}
    }
    byte[] finish(){return Arrays.copyOf(bytes,(pos+7)/8);}
  }
  static final class Reader {
    final byte[] bytes;int pos;
    Reader(byte[] b){bytes=b;}
    long get(int w){if(w<0||w>64||pos+w>bytes.length*8)throw new IllegalArgumentException("truncated stream");
      long v=0;while(w>0){int n=Math.min(8-(pos&7),w);v=(v<<n)|((bytes[pos>>>3]>>>(8-(pos&7)-n))&((1<<n)-1));pos+=n;w-=n;}return v;}
  }
  static final class Group {
    int start,end,pred,t,w,max,patches; boolean seed; long cost;
  }
  static long residual(long[] x,int i,int pred,int reset){
    long prev=x[i-1],d=x[i]-prev;
    return pred==0?x[i]^prev:zig(pred==1?d:d-(i==reset+1?0:prev-x[i-2]));
  }
  static Group group(long[] x,int start,int end,boolean seed){
    Group g=new Group();g.start=start;g.end=end;g.seed=seed;g.cost=Long.MAX_VALUE;
    int first=start+(seed?1:0),n=end-first;
    long[] codes=new long[n];int[] hist=new int[65];
    for(int p=0;p<3;p++){
      long all=0;
      for(int j=0;j<n;j++){long c=residual(x,first+j,p,seed?start:-2);codes[j]=c;all|=c;}
      int t=all==0?0:Long.numberOfTrailingZeros(all);Arrays.fill(hist,0);int max=0;
      for(long c:codes){int w=width(c>>>t);hist[w]++;max=Math.max(max,w);}
      int more=n-hist[0];
      for(int w=0;w<=max;w++){
        if(w>0)more-=hist[w];
        long cost=(seed?64:0)+GROUP_HEADER_BITS+(long)n*w+more*(POSITION_BITS+(long)max-w);
        if(cost<g.cost){g.cost=cost;g.pred=p;g.t=t;g.w=w;g.max=max;g.patches=more;}
      }
    }
    return g;
  }
  static final class View {
    final int kind,scale;final long[] x,raw;final boolean[] ex;final int[] exc;
    Group[] first,normal;long[] prefix;
    View(double[] values,int kind,int scale){
      this.kind=kind;this.scale=scale;int n=values.length;
      x=new long[n];raw=new long[n];ex=new boolean[n];exc=new int[n+1];
      for(int i=0;i<n;i++){
        double v=values[i];raw[i]=bits(v);
        if(kind==2)x[i]=key(raw[i]);
        else {
          double scaled=v*POW[scale];long q=Math.round(scaled);
          boolean exact=Double.isFinite(v)&&Math.abs(scaled)<0x1.0p62&&bits(q/POW[scale])==raw[i];
          ex[i]=!exact;x[i]=exact?q:0;
        }
        exc[i+1]=exc[i]+(ex[i]?1:0);
      }
      int count=(n+GROUP-1)/GROUP;first=new Group[count];normal=new Group[count];prefix=new long[count+1];
      for(int g=0;g<count;g++){
        int s=g*GROUP,e=Math.min(n,s+GROUP);first[g]=group(x,s,e,true);
        normal[g]=g==0?first[g]:group(x,s,e,false);prefix[g+1]=prefix[g]+normal[g].cost;
      }
    }
  }
  static int[] scales(double[] v){
    int n=Math.min(SAMPLE,v.length);long[] costs=new long[17];Integer[] order=new Integer[17];
    for(int s=0;s<=16;s++){
      long prev=0,cost=0;
      for(int i=0;i<n;i++){
        double z=v[i]*POW[s];long q=Math.round(z);
        boolean ok=Double.isFinite(v[i])&&Math.abs(z)<0x1.0p62&&bits(q/POW[s])==bits(v[i]);
        long x=ok?q:0;cost+=ok?width(zig(x-prev)):65;prev=x;
      }
      costs[s]=cost;order[s]=s;
    }
    Arrays.sort(order,(i,j)->costs[i]!=costs[j]?Long.compare(costs[i],costs[j]):Integer.compare(i,j));
    int[] selected=new int[CANDIDATES];for(int j=0;j<CANDIDATES;j++)selected[j]=order[j];return selected;
  }
  static final class Segment {
    int start,end;View view;long cost;
  }
  static Segment segment(View v,int start,int end,int blockLength){
    Segment s=new Segment();s.start=start;s.end=end;s.view=v;
    int n=end-start;s.cost=2+lengthBits(blockLength);
    if(v==null){s.cost+=64L*n;return s;}
    if(v.kind==1){int ex=v.exc[end]-v.exc[start];s.cost+=6+(ex>0?n+64L*ex:0);}
    int first=start/GROUP,last=(end+GROUP-1)/GROUP;
    s.cost+=v.first[first].cost+v.prefix[last]-v.prefix[first+1];
    return s;
  }
  public static final class Plan {
    final double[] input;final Segment[] segments;final long bits;final int bytes;final boolean raw;
    Plan(double[] in,Segment[] s,long bits,boolean raw){input=in;segments=s;this.bits=bits;bytes=(int)((bits+7)/8);this.raw=raw;}
  }
  public Plan plan(double[] in){
    int n=in.length;if(n>65535)throw new IllegalArgumentException("block length");
    long raw=24+64L*n;
    if(n==0)return new Plan(in,new Segment[0],raw,true);
    int[] scale=scales(in);
    View[] views=new View[CANDIDATES+1];
    for(int j=0;j<CANDIDATES;j++)views[j]=new View(in,1,scale[j]);
    views[CANDIDATES]=new View(in,2,0);
    int m=(n+GROUP-1)/GROUP;
    int[] cut=new int[m+1];
    for(int i=0;i<m;i++)cut[i]=i*GROUP;
    cut[m]=n;
    Segment[][] best=new Segment[m][m+1];
    for(int i=0;i<m;i++){
      for(int j=i+1;j<=m;j++){
        Segment current=segment(null,cut[i],cut[j],n);
        for(View v:views){
          // A lower bound above the current cost cannot improve this segment.
          long lower=2+lengthBits(n)+(v.kind==1?6:0)+v.first[cut[i]/GROUP].cost;
          if(lower>current.cost)continue;
          Segment s=segment(v,cut[i],cut[j],n);if(s.cost<current.cost)current=s;
        }
        best[i][j]=current;
      }
    }
    int limit=Math.min(MAX_SEGMENTS,m);long[][] dp=new long[limit+1][m+1];int[][] prev=new int[limit+1][m+1];
    for(long[] row:dp)Arrays.fill(row,Long.MAX_VALUE/4);dp[0][0]=0;
    for(int k=1;k<=limit;k++)for(int j=1;j<=m;j++)for(int i=k-1;i<j;i++){
      long c=dp[k-1][i]+best[i][j].cost;
      if(c<dp[k][j]){dp[k][j]=c;prev[k][j]=i;}
    }
    int k=1;for(int t=2;t<=limit;t++)if(dp[t][m]<dp[k][m])k=t;
    long cost=29+dp[k][m];
    if((cost+7)/8>=(raw+7)/8)return new Plan(in,new Segment[0],raw,true);
    Segment[] selected=new Segment[k];int j=m;
    for(int t=k;t>0;t--){int i=prev[t][j];selected[t-1]=best[i][j];j=i;}
    return new Plan(in,selected,cost,false);
  }
  static void writeGroup(Writer out,Group g,long[] x){
    int start=g.start+(g.seed?1:0);if(g.seed)out.put(x[g.start],64);
    out.put(g.pred,2);out.put(g.t,6);out.put(g.w,7);out.put(g.max,7);out.put(g.patches,COUNT_BITS);
    for(int i=start;i<g.end;i++){long c=residual(x,i,g.pred,g.seed?g.start:-2)>>>g.t;out.put(c,g.w);}
    for(int i=start;i<g.end;i++){
      long c=residual(x,i,g.pred,g.seed?g.start:-2)>>>g.t;
      if(width(c)>g.w){out.put(i-start,POSITION_BITS);out.put(c>>>g.w,g.max-g.w);}
    }
  }
  public byte[] encode(double[] in){
    return write(plan(in));
  }
  public byte[] write(Plan p){
    Writer out=new Writer();out.put(p.raw?0:1,8);out.put(p.input.length,16);
    if(p.raw)for(double v:p.input)out.put(bits(v),64);
    else{
      out.put(p.segments.length,5);
      for(Segment s:p.segments){
        View v=s.view;int n=s.end-s.start;out.put(v==null?0:v.kind,2);out.put(n-1,lengthBits(p.input.length));
        if(v==null){for(int i=s.start;i<s.end;i++)out.put(bits(p.input[i]),64);continue;}
        if(v.kind==1){
          int ex=v.exc[s.end]-v.exc[s.start];out.put(v.scale,5);out.put(ex>0?1:0,1);
          if(ex>0)for(int i=s.start;i<s.end;i++)out.put(v.ex[i]?1:0,1);
        }
        for(int i=s.start/GROUP,e=(s.end+GROUP-1)/GROUP;i<e;i++)
          writeGroup(out,i==s.start/GROUP?v.first[i]:v.normal[i],v.x);
        if(v.kind==1)for(int i=s.start;i<s.end;i++)if(v.ex[i])out.put(v.raw[i],64);
      }
    }
    if(out.pos!=p.bits)throw new AssertionError("cost model "+out.pos+" != "+p.bits);
    return out.finish();
  }
  public double[] decode(byte[] data){
    Reader in=new Reader(data);int format=(int)in.get(8),n=(int)in.get(16);double[] out=new double[n];
    if(format==0){for(int i=0;i<n;i++)out[i]=Double.longBitsToDouble(in.get(64));return out;}
    if(format!=1)throw new IllegalArgumentException("format");
    int count=(int)in.get(5),pos=0;
    if(count<1||count>16)throw new IllegalArgumentException("segment count");
    for(int s=0;s<count;s++){
      int kind=(int)in.get(2),len=(int)in.get(lengthBits(n))+1,end=pos+len;
      if(end>n||kind>2)throw new IllegalArgumentException("segment length/view");
      if(kind==0){for(int i=pos;i<end;i++)out[i]=Double.longBitsToDouble(in.get(64));pos=end;continue;}
      int scale=0;boolean[] ex=new boolean[len];
      if(kind==1){scale=(int)in.get(5);if(scale>16)throw new IllegalArgumentException("scale");if(in.get(1)!=0)for(int i=0;i<len;i++)ex[i]=in.get(1)!=0;}
      long[] x=new long[len];
      for(int g=0;g<len;g+=GROUP){
        int e=Math.min(len,g+GROUP);
        int first=g;if(g==0)x[first++]=in.get(64);
        int pred=(int)in.get(2),t=(int)in.get(6),w=(int)in.get(7),max=(int)in.get(7),patches=(int)in.get(COUNT_BITS);
        if(pred>2||w>64||max<w||max>64||patches>e-first)throw new IllegalArgumentException("group header");
        long[] c=new long[e-first];for(int j=0;j<c.length;j++)c[j]=in.get(w);
        for(int j=0;j<patches;j++){int i=(int)in.get(POSITION_BITS);if(i>=c.length||w==64)throw new IllegalArgumentException("patch");c[i]|=in.get(max-w)<<w;}
        for(int i=first;i<e;i++){long code=c[i-first]<<t,r=pred==0?code:unzig(code),prev=x[i-1];
          x[i]=pred==0?prev^r:pred==1?prev+r:prev+(i==1?0:prev-x[i-2])+r;}
      }
      for(int i=0;i<len;i++)out[pos+i]=kind==2?Double.longBitsToDouble(unkey(x[i])):x[i]/POW[scale];
      if(kind==1)for(int i=0;i<len;i++)if(ex[i])out[pos+i]=Double.longBitsToDouble(in.get(64));
      pos=end;
    }
    if(pos!=n)throw new IllegalArgumentException("incomplete segments");
    return out;
  }
}
