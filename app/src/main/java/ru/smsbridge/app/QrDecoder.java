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
        }
        throw new UserError("Не удалось прочитать QR-код. Обрезанные изображения поддерживаются. Проверь, что все квадраты кода целиком видны; можно отправить оригинал как файл или строку LPA:1$…");
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
