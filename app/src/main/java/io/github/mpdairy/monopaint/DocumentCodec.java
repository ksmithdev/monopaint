package io.github.mpdairy.monopaint;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/** Versioned, bounded, checksummed logical document; never stores display dots. */
final class DocumentCodec {
    private static final int MAGIC = 0x54534d31; // TSM1
    private static final int LAYER_MAGIC = 0x54534d32; // TSM2
    private static final int OPACITY_MAGIC = 0x54534d33; // TSM3
    /** TSM3 followed by the page's masking fluid; pages without any keep writing TSM3. */
    private static final int MASK_MAGIC = 0x54534d34; // TSM4
    /** A reference image under the layers; TSM7+ also stores its lock byte, which is what we write now. */
    private static final int REFERENCE_MAGIC = 0x54534d35; // TSM5
    private static final int REFERENCE_MASK_MAGIC = 0x54534d36; // TSM6
    private static final int REFERENCE_LOCK_MAGIC = 0x54534d37; // TSM7
    private static final int REFERENCE_LOCK_MASK_MAGIC = 0x54534d38; // TSM8
    static final int MAX_REFERENCE_PIXELS = ToneDocument.MAX_PIXELS;
    static void write(OutputStream stream, ToneDocument.Snapshot snapshot) throws IOException {
        boolean reference = !snapshot.layers.isEmpty() && snapshot.layers.get(0).readOnly;
        ReferenceImage image = reference ? snapshot.layers.get(0).reference : null;
        int magic = reference ? (snapshot.mask != null ? REFERENCE_LOCK_MASK_MAGIC : REFERENCE_LOCK_MAGIC)
                : (snapshot.mask != null ? MASK_MAGIC : OPACITY_MAGIC);
        DataOutputStream header=new DataOutputStream(stream);
        header.writeInt(magic); header.writeInt(snapshot.width); header.writeInt(snapshot.height);
        java.util.zip.Deflater deflater=new java.util.zip.Deflater();
        try {
            DeflaterOutputStream compressed=new DeflaterOutputStream(stream,deflater);
            CRC32 crc=new CRC32();
            DataOutputStream data=new DataOutputStream(new java.util.zip.CheckedOutputStream(compressed,crc));
            data.writeInt(snapshot.layers.size()); data.writeInt(snapshot.active);
            if(reference) {
                data.writeInt(image.width); data.writeInt(image.height);
                for(float value:image.transform) data.writeFloat(value);
                data.writeBoolean(snapshot.layers.get(0).locked);
            }
            for(ToneDocument.Layer layer:snapshot.layers) {
                data.writeUTF(layer.name); data.writeBoolean(layer.visible); data.writeByte(layer.opacity);
                data.write(layer.tones); data.write(layer.alpha);
                if(reference && snapshot.layers.get(0)==layer) { data.write(image.lum); data.write(image.alpha); }
            }
            if(snapshot.mask!=null) { data.write(snapshot.mask.tones); data.write(snapshot.mask.alpha); }
            data.flush(); new DataOutputStream(compressed).writeLong(crc.getValue());
            compressed.finish(); compressed.flush();
        } finally { deflater.end(); }
    }
    private static ToneDocument readLayers(InputStream stream,int width,int height,boolean retain,boolean hasOpacity,boolean hasMask,boolean hasReference,boolean hasLock) throws IOException {
        java.util.zip.Inflater inflater=new java.util.zip.Inflater();
        try {
            InflaterInputStream compressed=new InflaterInputStream(stream,inflater);
            CRC32 crc=new CRC32();
            DataInputStream data=new DataInputStream(new java.util.zip.CheckedInputStream(compressed,crc));
            int count=data.readInt(),active=data.readInt();
            if(count<1||count>ToneDocument.MAX_STACK||active<0||active>=count) throw new IOException("Invalid layers");
            ReferenceImage reference=null;
            boolean referenceLocked=false;
            int refPixels=0;
            if(hasReference) {
                int refWidth=data.readInt(), refHeight=data.readInt();
                if(refWidth<1||refHeight<1||(long)refWidth*refHeight>MAX_REFERENCE_PIXELS) throw new IOException("Invalid reference image size");
                refPixels=refWidth*refHeight;
                float[] transform=new float[6];
                for(int i=0;i<6;i++) { transform[i]=data.readFloat(); if(!Float.isFinite(transform[i])) throw new IOException("Invalid reference placement"); }
                referenceLocked=hasLock&&data.readBoolean();
                // The picture's own planes live inside layer 0's block, read or consumed there.
                if(retain) reference=new ReferenceImage(new byte[refPixels],new byte[refPixels],refWidth,refHeight,transform);
            }
            java.util.ArrayList<ToneDocument.Layer> layers=new java.util.ArrayList<>();
            for(int i=0;i<count;i++) {
                String name=data.readUTF(); int visible=data.readUnsignedByte();
                int opacity=hasOpacity?data.readUnsignedByte():100;
                if(opacity>100)throw new IOException("Invalid layer opacity");
                if(name.trim().isEmpty()||name.length()>40||visible>1) throw new IOException("Invalid layer information");
                if(retain) {
                    byte[] tones=new byte[width*height],alpha=new byte[width*height];
                    data.readFully(tones); data.readFully(alpha);
                    ToneDocument.Layer layer=new ToneDocument.Layer(name,visible==1,tones,alpha);
                    layer.opacity=opacity;
                    if(hasReference&&i==0) {
                        layer.readOnly=true; layer.locked=referenceLocked; layer.reference=reference;
                        data.readFully(reference.lum); data.readFully(reference.alpha);
                    }
                    layers.add(layer);
                } else {
                    consume(data,width*height*2);
                    if(hasReference&&i==0) consume(data,refPixels*2);
                }
            }
            ToneDocument.Layer mask=null;
            if(hasMask&&retain) {
                byte[] tones=new byte[width*height],alpha=new byte[width*height];
                data.readFully(tones); data.readFully(alpha);
                mask=new ToneDocument.Layer("Masking fluid",true,tones,alpha);
            } else if(hasMask) consume(data,width*height*2);
            long checksum=crc.getValue();
            if(new DataInputStream(compressed).readLong()!=checksum) throw new IOException("Painting checksum mismatch");
            if(compressed.read()!=-1) throw new IOException("Extra painting data");
            return retain?new ToneDocument(width,height,layers,active,mask):null;
        } finally { inflater.end(); }
    }
    static void write(OutputStream stream, int width, int height, byte[] tones) throws IOException {
        ToneDocument.validateSize(width, height);
        if (tones.length != width * height) throw new IOException("Wrong tone count");
        DataOutputStream header = new DataOutputStream(stream);
        CRC32 crc = new CRC32(); crc.update(tones);
        header.writeInt(MAGIC); header.writeInt(width); header.writeInt(height); header.writeLong(crc.getValue());
        java.util.zip.Deflater deflater=new java.util.zip.Deflater();
        try {
            DeflaterOutputStream compressed = new DeflaterOutputStream(stream,deflater);
            compressed.write(tones); compressed.finish(); compressed.flush();
        } finally {deflater.end();}
    }
    static ToneDocument read(InputStream stream) throws IOException { return read(stream,true,0,0); }
    static void validate(InputStream stream,int width,int height) throws IOException { read(stream,false,width,height); }
    private static void consume(DataInputStream input,int count) throws IOException {
        byte[] buffer=new byte[Math.min(count,8192)];
        while(count>0) { int chunk=Math.min(count,buffer.length); input.readFully(buffer,0,chunk); count-=chunk; }
    }
    private static ToneDocument read(InputStream stream,boolean retain,int expectedWidth,int expectedHeight) throws IOException {
        DataInputStream header = new DataInputStream(stream);
        int magic=header.readInt();
        boolean known = magic==MAGIC || magic==LAYER_MAGIC || magic==OPACITY_MAGIC || magic==MASK_MAGIC
                || magic==REFERENCE_MAGIC || magic==REFERENCE_MASK_MAGIC
                || magic==REFERENCE_LOCK_MAGIC || magic==REFERENCE_LOCK_MASK_MAGIC;
        if (!known) throw new IOException("Unknown painting format");
        int width = header.readInt(), height = header.readInt();
        try { ToneDocument.validateSize(width, height); }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid painting dimensions", invalid); }
        if(expectedWidth!=0&&(width!=expectedWidth||height!=expectedHeight)) throw new IOException("Page size mismatch");
        if(magic==LAYER_MAGIC || magic==OPACITY_MAGIC || magic==MASK_MAGIC)
            return readLayers(stream,width,height,retain,magic!=LAYER_MAGIC,magic==MASK_MAGIC,false,false);
        if(magic>=REFERENCE_MAGIC)
            return readLayers(stream,width,height,retain,true,
                    magic==REFERENCE_MASK_MAGIC||magic==REFERENCE_LOCK_MASK_MAGIC,
                    true,magic>=REFERENCE_LOCK_MAGIC);
        long checksum=header.readLong();
        byte[] tones = retain?new byte[width * height]:null;
        CRC32 crc = new CRC32();
        java.util.zip.Inflater inflater=new java.util.zip.Inflater();
        try {
            DataInputStream compressed = new DataInputStream(new java.util.zip.CheckedInputStream(new InflaterInputStream(stream,inflater),crc));
            if(retain) compressed.readFully(tones); else consume(compressed,width*height);
            if (compressed.read() != -1) throw new IOException("Extra painting data");
        } finally {inflater.end();}
        if (crc.getValue() != checksum) throw new IOException("Painting checksum mismatch");
        if(!retain) return null;
        byte[] alpha=new byte[width*height]; java.util.Arrays.fill(alpha,(byte)255);
        java.util.ArrayList<ToneDocument.Layer> layers=new java.util.ArrayList<>();
        layers.add(new ToneDocument.Layer("Layer 1",true,tones,alpha));
        return new ToneDocument(width,height,layers,0);
    }
    private DocumentCodec() {}
}
