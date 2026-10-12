package ru.smsbridge.app;

import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.EnumMap;
import org.junit.Test;
import static org.junit.Assert.*;

public class QrDecoderTest {
    static final String CODE="LPA:1$smdp.example.test$SYNTHETIC-NON-ACTIVATABLE-TEST-0123456789";
    static Picture qr(String content,int scale,int dark,int light) throws Exception {
        EnumMap<EncodeHintType,Object> hints=new EnumMap<>(EncodeHintType.class);hints.put(EncodeHintType.MARGIN,0);
        BitMatrix matrix=new QRCodeWriter().encode(content,BarcodeFormat.QR_CODE,1,1,hints);
        Picture image=new Picture(matrix.getWidth()*scale,matrix.getHeight()*scale,2);
        for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)image.setRGB(x,y,matrix.get(x/scale,y/scale)?dark:light);
        return image;
    }
    static final class Picture {
        final int width,height;final int[] data;
        Picture(int width,int height,int ignored){this.width=width;this.height=height;data=new int[width*height];}
        int getWidth(){return width;}int getHeight(){return height;}
        int getRGB(int x,int y){return data[y*width+x];}
        void setRGB(int x,int y,int value){data[y*width+x]=value;}
        void setRGB(int x,int y,int w,int h,int[] source,int offset,int stride){for(int row=0;row<h;row++)System.arraycopy(source,offset+row*stride,data,(y+row)*width+x,w);}
    }
    static int[] pixels(Picture image){return image.data.clone();}
    static String decode(Picture image) throws Exception{return QrDecoder.decode(image.getWidth(),image.getHeight(),pixels(image));}
    static Picture roundTrip(Picture image,String format) throws Exception {
        // Android's compile API excludes java.desktop. Local JVM unit tests can use ImageIO
        // via reflection, so PNG/JPEG are still actually encoded and decoded in both CI builds.
        Class<?> bitmap=Class.forName("java.awt.image.BufferedImage"),io=Class.forName("javax.imageio.ImageIO");
        Object input=bitmap.getConstructor(int.class,int.class,int.class).newInstance(image.width,image.height,format.equals("jpg")?1:2);
        bitmap.getMethod("setRGB",int.class,int.class,int.class,int.class,int[].class,int.class,int.class)
            .invoke(input,0,0,image.width,image.height,image.data,0,image.width);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        assertEquals(Boolean.TRUE,io.getMethod("write",Class.forName("java.awt.image.RenderedImage"),String.class,java.io.OutputStream.class).invoke(null,input,format,bytes));
        Object decoded=io.getMethod("read",java.io.InputStream.class).invoke(null,new ByteArrayInputStream(bytes.toByteArray()));
        int width=(Integer)bitmap.getMethod("getWidth").invoke(decoded),height=(Integer)bitmap.getMethod("getHeight").invoke(decoded);
        int[] data=(int[])bitmap.getMethod("getRGB",int.class,int.class,int.class,int.class,int[].class,int.class,int.class)
            .invoke(decoded,0,0,width,height,null,0,width);
        Picture result=new Picture(width,height,2);result.setRGB(0,0,width,height,data,0,width);return result;
    }
    @Test public void exactCropWithNoQuietZoneReadsEveryModule() throws Exception {assertEquals(CODE,decode(qr(CODE,4,0xff000000,0xffffffff)));}
    @Test public void onePixelModulesCanBeReadWithoutScreenshotFrame() throws Exception {assertEquals(CODE,decode(qr(CODE,1,0xff000000,0xffffffff)));}
    @Test public void transparentPngReproducesOldFailureAndDecodes() throws Exception {
        Picture image=roundTrip(qr(CODE,4,0xff000000,0x00000000),"png");
        assertThrows(ReaderException.class,()->new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(image.getWidth(),image.getHeight(),pixels(image))))));
        assertEquals(CODE,decode(image));
    }
    @Test public void partlyTransparentModulesAreComposited() throws Exception {assertEquals(CODE,decode(qr(CODE,4,0x90000000,0x00000000)));}
    @Test public void whiteQrOnTransparentPngIsSupported() throws Exception {assertEquals(CODE,decode(roundTrip(qr(CODE,4,0xffffffff,0x00000000),"png")));}
    @Test public void opaqueInvertedQrIsSupported() throws Exception {assertEquals(CODE,decode(qr(CODE,4,0xffffffff,0xff000000)));}
    @Test public void croppedJpegRoundTripIsSupported() throws Exception {assertEquals(CODE,decode(roundTrip(qr(CODE,5,0xff000000,0xffffffff),"jpg")));}
    @Test public void rightAngleRotationsOfTightCropAreSupported() throws Exception {
        Picture image=qr(CODE,3,0xff000000,0xffffffff);
        for(int i=0;i<4;i++){
            assertEquals(CODE,decode(image));Picture rotated=new Picture(image.getHeight(),image.getWidth(),2);
            for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)rotated.setRGB(image.getHeight()-1-y,x,image.getRGB(x,y));
            image=rotated;
        }
    }
    @Test public void phoneScreenshotAndWideImageBothReadExactPayload() throws Exception {
        Picture code=qr(CODE,3,0xff000000,0xffffffff);
        for(int[] size:new int[][]{{540,1000},{700,210},{230,500}}) {
            Picture image=new Picture(size[0],size[1],2);
            int[] background=new int[size[0]*size[1]];Arrays.fill(background,0xffeeeeee);image.setRGB(0,0,size[0],size[1],background,0,size[0]);
            image.setRGB((size[0]-code.getWidth())/2,(size[1]-code.getHeight())/2,code.getWidth(),code.getHeight(),pixels(code),0,code.getWidth());
            for(int x=10;x<80;x++)for(int y=10;y<14;y++)image.setRGB(x,y,0xff222222);
            assertEquals(CODE,decode(image));
        }
    }
    @Test public void grayLowContrastQrStillReads() throws Exception {assertEquals(CODE,decode(qr(CODE,5,0xff777777,0xffcccccc)));}
    @Test public void decoderDoesNotChangeQrContentOrInputPixels() throws Exception {
        String text="LPA:1$example.test$Case-SENSITIVE+$";Picture image=qr(text,3,0xff000000,0xffffffff);
        int[] input=pixels(image),before=input.clone();assertEquals(text,QrDecoder.decode(image.getWidth(),image.getHeight(),input));assertArrayEquals(before,input);
    }
    @Test(timeout=5000) public void blankImageReturnsUsefulErrorInsteadOfBackgroundAdvice() throws Exception {
        int[] white=new int[300*150];Arrays.fill(white,0xffffffff);
        UserError e=assertThrows(UserError.class,()->QrDecoder.decode(300,150,white));
        assertTrue(e.getMessage().contains("Обрезанные изображения поддерживаются"));assertFalse(e.getMessage().contains("без лишнего фона"));
    }
    @Test public void impossibleImageDimensionsAreRejectedBeforeAllocation() {
        assertThrows(UserError.class,()->QrDecoder.decode(Integer.MAX_VALUE,2,new int[0]));
        assertThrows(UserError.class,()->QrDecoder.decode(0,100,new int[0]));
        assertThrows(UserError.class,()->QrDecoder.decode(50,50,new int[1]));
    }
    /** A small QR enlarged with bilinear smoothing, then cropped tightly: every module is a ramp. */
    static int[] enlargedCrop(int px,int margin) throws Exception {
        EnumMap<EncodeHintType,Object> hints=new EnumMap<>(EncodeHintType.class);hints.put(EncodeHintType.MARGIN,margin);
        hints.put(EncodeHintType.ERROR_CORRECTION,com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.H);
        BitMatrix m=new QRCodeWriter().encode(CODE,BarcodeFormat.QR_CODE,1,1,hints);int n=m.getWidth();int[] out=new int[px*px];
        for(int y=0;y<px;y++)for(int x=0;x<px;x++) {
            double fx=Math.max(0,Math.min(n-1,(x+.5)*n/px-.5)),fy=Math.max(0,Math.min(n-1,(y+.5)*n/px-.5));
            int x0=(int)fx,y0=(int)fy,x1=Math.min(n-1,x0+1),y1=Math.min(n-1,y0+1);double ax=fx-x0,ay=fy-y0;
            double d=(m.get(x0,y0)?1:0)*(1-ax)*(1-ay)+(m.get(x1,y0)?1:0)*ax*(1-ay)+(m.get(x0,y1)?1:0)*(1-ax)*ay+(m.get(x1,y1)?1:0)*ax*ay;
            int v=(int)Math.round(255-d*255);out[y*px+x]=0xFF000000|(v<<16)|(v<<8)|v;
        }
        return out;
    }
    @Test public void largeSmoothedCleanCropsAreRead() throws Exception {
        // v0.19 failed on 1200/1, 1600/1, 1600/2, 2000/1 and 2000/2: typical operator QR images.
        for(int px:new int[]{700,1200,1600,2000})for(int margin=0;margin<=2;margin++)
            assertEquals(px+"/"+margin,CODE,QrDecoder.decode(px,px,enlargedCrop(px,margin)));
    }
    @Test public void reducedCopiesComeFirstAndOriginalLast() {
        assertArrayEquals(new int[]{3,5,1},QrDecoder.factors(2000,2000));
        assertArrayEquals(new int[]{1},QrDecoder.factors(500,500));
        assertArrayEquals(new int[]{2,7,8},QrDecoder.enlargements(114));
    }
    /** Operator-styled QR on a card: rounded finder squares, a logo in the middle, smooth edges. */
    static int[] styled(double module,int[] size) throws Exception {
        EnumMap<EncodeHintType,Object> hints=new EnumMap<>(EncodeHintType.class);hints.put(EncodeHintType.MARGIN,0);
        hints.put(EncodeHintType.ERROR_CORRECTION,com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.H);
        BitMatrix m=new QRCodeWriter().encode(CODE,BarcodeFormat.QR_CODE,1,1,hints);int n=m.getWidth();
        int qr=(int)Math.round(n*module),pad=(int)(qr*0.9);int w=qr+2*pad,h=qr+pad*3/2;size[0]=w;size[1]=h;int[] out=new int[w*h];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){int dark=0;
            for(int sy=0;sy<3;sy++)for(int sx=0;sx<3;sx++)if(styledDark(m,n,(x-pad+(sx+.5)/3)/module,(y-pad/2+(sy+.5)/3)/module))dark++;
            int v=250-dark*220/9;out[y*w+x]=0xFF000000|(v<<16)|(v<<8)|v;}
        return out;
    }
    private static boolean round(double x,double y,double x0,double y0,double s,double r) {
        if(x<x0||y<y0||x>=x0+s||y>=y0+s)return false;
        double cx=Math.max(x0+r,Math.min(x0+s-r,x)),cy=Math.max(y0+r,Math.min(y0+s-r,y));return (x-cx)*(x-cx)+(y-cy)*(y-cy)<=r*r;
    }
    private static boolean styledDark(BitMatrix m,int n,double fx,double fy) {
        if(fx<0||fy<0||fx>=n||fy>=n)return false;
        for(double[] f:new double[][]{{0,0},{n-7,0},{0,n-7}}){double x0=f[0],y0=f[1];
            if(fx>=x0&&fx<x0+7&&fy>=y0&&fy<y0+7)return round(fx,fy,x0,y0,7,2)&&!round(fx,fy,x0+1,y0+1,5,1.5)||round(fx,fy,x0+2,y0+2,3,1);}
        double c=n/2.0;
        if(Math.abs(fx-c)<3.5&&Math.abs(fy-c)<3.5)return round(fx,fy,c-3.5,c-3.5,7,1.5)&&!round(fx,fy,c-2.5,c-2.5,5,1)||round(fx,fy,c-1.5,c-1.5,3,.5);
        return m.get((int)fx,(int)fy);
    }
    @Test public void styledOperatorCodesWithRoundedFindersAreRead() throws Exception {
        // v0.20.0 failed at 4.25, 5.25, 6.25 and 7.25 px modules, v0.21.0 at 4.25 px (as on a real operator card).
        for(double module=3.5;module<=8.01;module+=0.25){int[] size=new int[2];int[] pixels=styled(module,size);
            assertEquals("module "+module,CODE,QrDecoder.decode(size[0],size[1],pixels));}
    }
}
