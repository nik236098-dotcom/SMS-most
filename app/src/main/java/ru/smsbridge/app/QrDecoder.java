package ru.smsbridge.app;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;

/** QR pixels from any image shape. No network or SIM operations; bounded image sizes and attempts. */
final class QrDecoder {
    static final int MAX_EDGE=2048;
    static String decode(int width,int height,int[] argb) throws UserError {
        if(width<=0||height<=0||width>MAX_EDGE||height>MAX_EDGE||argb==null||(long)width*height!=argb.length)
            throw new UserError("Не удалось прочитать размеры изображения QR-кода.");
        boolean transparent=false;
        for(int pixel:argb)if((pixel>>>24)!=255){transparent=true;break;}
        // PNG alpha is meaningful: transparent black pixels must not become black background.
        // Also support white modules on a transparent background by compositing onto black.
        for(int background=0;background<(transparent?2:1);background++) {
            byte[] gray=new byte[argb.length];int backdrop=background==0?255:0;
            for(int i=0;i<argb.length;i++) {
                int p=argb[i],alpha=p>>>24,light=(((p>>>16)&255)+2*((p>>>8)&255)+(p&255))/4;
                gray[i]=(byte)((light*alpha+backdrop*(255-alpha)+127)/255);
            }
            // A clean cropped code is often large with smoothed module edges; ZXing misses some
            // of those at full size. Reduced copies (also much faster) are tried first.
            for(int factor:factors(width,height)) {
                int w=width/factor,h=height/factor;
                String text=attempts(w,h,factor==1?gray:shrink(gray,width,height,factor));if(text!=null)return text;
            }
            // Small, blurry codes (e.g. a JPEG-compressed thumbnail): a smooth enlargement keeps
            // module edges where nearest-neighbour scaling leaves them jagged.
            int edge=Math.max(width,height);
            if(edge<500)for(int k:enlargements(edge)) {
                String text=attempts(width*k,height*k,enlarge(gray,width,height,k));if(text!=null)return text;
            }
            // Styled codes (rounded finder squares, a logo in the middle) are found by ZXing only at
            // some module sizes. A few fractional, smoothed scales cover those; output stays small.
            for(float scale:SCALES) {
                int w=Math.round(width*scale),h=Math.round(height*scale);
                if(Math.min(w,h)<42||Math.max(w,h)>1300)continue;
                String text=attempts(w,h,resample(gray,width,height,w,h));if(text!=null)return text;
            }
        }
        throw new UserError("Не удалось прочитать QR-код. Обрезанные изображения поддерживаются. Проверь, что все квадраты кода целиком видны; можно отправить оригинал как файл или строку LPA:1$…");
    }
    /** Integer reductions bringing the long edge near 700 and 400 px, then the original. */
    static int[] factors(int width,int height) {
        int edge=Math.max(width,height);java.util.LinkedHashSet<Integer> out=new java.util.LinkedHashSet<>();
        for(int target:new int[]{700,400}){int f=Math.round(edge/(float)target);if(f>=2&&Math.min(width,height)/f>=42)out.add(f);}
        out.add(1);int[] list=new int[out.size()];int i=0;for(int f:out)list[i++]=f;return list;
    }
    /** Small images: a few smooth enlargement factors; module edges land differently for each. */
    static int[] enlargements(int edge) {
        java.util.LinkedHashSet<Integer> out=new java.util.LinkedHashSet<>();
        for(int target:new int[]{0,700,900}){int k=target==0?2:(target+edge-1)/edge;if(k>=2&&k<=8)out.add(k);}
        int[] list=new int[out.size()];int i=0;for(int k:out)list[i++]=k;return list;
    }
    static final float[] SCALES={0.6f,0.75f,0.5f,1.5f,1.25f,2f};
    /** Area average when reducing, bilinear when enlarging. */
    static byte[] resample(byte[] gray,int width,int height,int w,int h) {
        byte[] out=new byte[w*h];float sx=width/(float)w,sy=height/(float)h;
        if(sx>1||sy>1) {
            for(int y=0;y<h;y++){int y0=(int)(y*sy),y1=Math.max(y0+1,Math.min(height,(int)((y+1)*sy)));
                for(int x=0;x<w;x++){int x0=(int)(x*sx),x1=Math.max(x0+1,Math.min(width,(int)((x+1)*sx)));int sum=0;
                    for(int yy=y0;yy<y1;yy++){int row=yy*width;for(int xx=x0;xx<x1;xx++)sum+=gray[row+xx]&255;}
                    out[y*w+x]=(byte)(sum/((y1-y0)*(x1-x0)));}}
            return out;
        }
        for(int y=0;y<h;y++) {
            float fy=Math.max(0,Math.min(height-1,(y+.5f)*sy-.5f));int y0=(int)fy,y1=Math.min(height-1,y0+1);float ay=fy-y0;
            for(int x=0;x<w;x++) {
                float fx=Math.max(0,Math.min(width-1,(x+.5f)*sx-.5f));int x0=(int)fx,x1=Math.min(width-1,x0+1);float ax=fx-x0;
                float top=(gray[y0*width+x0]&255)*(1-ax)+(gray[y0*width+x1]&255)*ax,bottom=(gray[y1*width+x0]&255)*(1-ax)+(gray[y1*width+x1]&255)*ax;
                out[y*w+x]=(byte)Math.round(top*(1-ay)+bottom*ay);
            }
        }
        return out;
    }
    private static byte[] shrink(byte[] gray,int width,int height,int f) {
        int w=width/f,h=height/f,area=f*f;byte[] out=new byte[w*h];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
            int sum=0;for(int dy=0;dy<f;dy++){int row=(y*f+dy)*width+x*f;for(int dx=0;dx<f;dx++)sum+=gray[row+dx]&255;}
            out[y*w+x]=(byte)((sum+area/2)/area);
        }
        return out;
    }
    private static byte[] enlarge(byte[] gray,int width,int height,int k) {
        int w=width*k,h=height*k;byte[] out=new byte[w*h];
        for(int y=0;y<h;y++) {
            float sy=Math.max(0,Math.min(height-1,(y+.5f)/k-.5f));int y0=(int)sy,y1=Math.min(height-1,y0+1);float fy=sy-y0;
            for(int x=0;x<w;x++) {
                float sx=Math.max(0,Math.min(width-1,(x+.5f)/k-.5f));int x0=(int)sx,x1=Math.min(width-1,x0+1);float fx=sx-x0;
                float top=(gray[y0*width+x0]&255)*(1-fx)+(gray[y0*width+x1]&255)*fx,bottom=(gray[y1*width+x0]&255)*(1-fx)+(gray[y1*width+x1]&255)*fx;
                out[y*w+x]=(byte)Math.round(top*(1-fy)+bottom*fy);
            }
        }
        return out;
    }
    private static String attempts(int width,int height,byte[] gray) {
        for(boolean inverted:new boolean[]{false,true}) {
            LuminanceSource source=prepare(width,height,gray,inverted);
            for(boolean global:new boolean[]{false,true}) {
                BinaryBitmap bitmap=new BinaryBitmap(global?new GlobalHistogramBinarizer(source):new HybridBinarizer(source));
                for(boolean pure:new boolean[]{false,true}) {
                    Map<DecodeHintType,Object> hints=new EnumMap<>(DecodeHintType.class);
                    hints.put(DecodeHintType.TRY_HARDER,true);
                    if(pure)hints.put(DecodeHintType.PURE_BARCODE,true);
                    try{return new QRCodeReader().decode(bitmap,hints).getText();}
                    catch(ReaderException ignored){/* Try the next bounded image interpretation. */}
                }
            }
        }
        return null;
    }
    private static LuminanceSource prepare(int width,int height,byte[] gray,boolean inverted) {
        int scale=Math.min(4,Math.max(1,(384+Math.max(width,height)-1)/Math.max(width,height)));
        int border=Math.max(16,Math.min(width,height)*scale/5);
        int w=width*scale+2*border,h=height*scale+2*border;
        byte[] pixels=new byte[w*h];Arrays.fill(pixels,(byte)255);
        for(int y=0;y<height*scale;y++)for(int x=0;x<width*scale;x++) {
            int value=gray[(y/scale)*width+x/scale]&255;
            pixels[(y+border)*w+x+border]=(byte)(inverted?255-value:value);
        }
        return new GraySource(w,h,pixels);
    }
    private static final class GraySource extends LuminanceSource {
        private final byte[] pixels;
        GraySource(int width,int height,byte[] pixels){super(width,height);this.pixels=pixels;}
        @Override public byte[] getRow(int y,byte[] row) {
            if(y<0||y>=getHeight())throw new IllegalArgumentException("Row outside image");
            if(row==null||row.length<getWidth())row=new byte[getWidth()];
            System.arraycopy(pixels,y*getWidth(),row,0,getWidth());return row;
        }
        @Override public byte[] getMatrix(){return pixels;}
    }
}
