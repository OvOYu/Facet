"""Independent Facet bitstream decoder. Does not import or call the new codec.
Verifies raw 64-bit integers, including all NaN payloads and signed zero.
"""
import struct

MASK=(1<<64)-1
class Bits:
    def __init__(self,data): self.data=int.from_bytes(data,'big');self.total=len(data)*8;self.pos=0
    def read(self,n):
        if n<0 or n>64 or self.pos+n>self.total: raise ValueError('bit range/truncation')
        self.pos+=n
        return (self.data>>(self.total-self.pos))&((1<<n)-1)
def signed(x):return x if x<(1<<63) else x-(1<<64)
def decode(data):
    r=Bits(data);fmt=r.read(8);n=r.read(16)
    if fmt==0: result=[r.read(64) for _ in range(n)]
    else:
        if fmt != 1:raise ValueError('format')
        group=64
        position_bits=6
        count=r.read(5)
        if count<1 or count>16:raise ValueError('segment count')
        result=[]
        for _ in range(count):
            mode=r.read(2);length=r.read(max(10,(max(1,n)-1).bit_length()))+1
            if mode==0:result.extend(r.read(64) for _ in range(length));continue
            if mode not in (1,2):raise ValueError('view')
            scale=0;exception=[False]*length
            if mode==1:
                scale=r.read(5)
                if scale>16:raise ValueError('scale')
                if r.read(1):exception=[bool(r.read(1)) for _ in range(length)]
            values=[]
            for start in range(0,length,group):
                end=min(length,start+group)
                if start==0:values.append(r.read(64))
                begin=len(values);pred=r.read(2);factor=r.read(6);width=r.read(7);high=r.read(7);patches=r.read(position_bits+1)
                if pred>2 or width>high or high>64 or patches>end-begin:raise ValueError('group')
                residual=[r.read(width) for _ in range(end-begin)]
                seen=set()
                for _ in range(patches):
                    where=r.read(position_bits)
                    if where>=len(residual) or where in seen or width==64:raise ValueError('patch position')
                    seen.add(where);residual[where]|=r.read(high-width)<<width
                for c in residual:
                    c=(c<<factor)&MASK
                    if pred==0: value=values[-1]^c
                    else:
                        delta=(c>>1)^-(c&1)
                        if pred==2 and len(values)>1:delta+=values[-1]-values[-2]
                        value=(values[-1]+delta)&MASK
                    values.append(value)
            if mode==2:
                decoded=[(x^(1<<63)) if x&(1<<63) else (~x&MASK) for x in values]
            else:
                pow10=float('1e%d'%scale)
                decoded=[struct.unpack('<Q',struct.pack('<d',signed(x)/pow10))[0] for x in values]
                for i,ex in enumerate(exception):
                    if ex:decoded[i]=r.read(64)
            result.extend(decoded)
        if len(result)!=n:raise ValueError('decoded length')
    if r.total-r.pos>7 or r.read(r.total-r.pos)!=0:raise ValueError('trailing non-padding data')
    return result
