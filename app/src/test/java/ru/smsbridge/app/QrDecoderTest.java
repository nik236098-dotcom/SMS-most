package ru.smsbridge.app;

import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.EnumMap;
import javax.imageio.ImageIO;
import org.junit.Test;
import static org.junit.Assert.*;

public class QrDecoderTest {
    static final String CODE="LPA:1$smdp.example.test$SYNTHETIC-NON-ACTIVATABLE-TEST-0123456789";
    static BufferedImage qr(String content,int scale,int dark,int light) throws Exception {
        EnumMap<EncodeHintType,Object> hints=new EnumMap<>(EncodeHintType.class);hints.put(EncodeHintType.MARGIN,0);
        BitMatrix matrix=new QRCodeWriter().encode(content,BarcodeFormat.QR_CODE,1,1,hints);
        BufferedImage image=new BufferedImage(matrix.getWidth()*scale,matrix.getHeight()*scale,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)image.setRGB(x,y,matrix.get(x/scale,y/scale)?dark:light);
        return image;
    }
    static int[] pixels(BufferedImage image){return image.getRGB(0,0,image.getWidth(),image.getHeight(),null,0,image.getWidth());}
    static String decode(BufferedImage image) throws Exception{return QrDecoder.decode(image.getWidth(),image.getHeight(),pixels(image));}
    static BufferedImage roundTrip(BufferedImage image,String format) throws Exception {
        if(format.equals("jpg")) {
            BufferedImage rgb=new BufferedImage(image.getWidth(),image.getHeight(),BufferedImage.TYPE_INT_RGB);
            rgb.setRGB(0,0,image.getWidth(),image.getHeight(),pixels(image),0,image.getWidth());image=rgb;
        }
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();assertTrue(ImageIO.write(image,format,bytes));
        return ImageIO.read(new ByteArrayInputStream(bytes.toByteArray()));
    }
    @Test public void exactCropWithNoQuietZoneReadsEveryModule() throws Exception {assertEquals(CODE,decode(qr(CODE,4,0xff000000,0xffffffff)));}
    @Test public void onePixelModulesCanBeReadWithoutScreenshotFrame() throws Exception {assertEquals(CODE,decode(qr(CODE,1,0xff000000,0xffffffff)));}
    @Test public void transparentPngReproducesOldFailureAndDecodes() throws Exception {
        BufferedImage image=roundTrip(qr(CODE,4,0xff000000,0x00000000),"png");
        assertThrows(ReaderException.class,()->new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(image.getWidth(),image.getHeight(),pixels(image))))));
        assertEquals(CODE,decode(image));
    }
    @Test public void partlyTransparentModulesAreComposited() throws Exception {assertEquals(CODE,decode(qr(CODE,4,0x90000000,0x00000000)));}
    @Test public void whiteQrOnTransparentPngIsSupported() throws Exception {assertEquals(CODE,decode(roundTrip(qr(CODE,4,0xffffffff,0x00000000),"png")));}
    @Test public void opaqueInvertedQrIsSupported() throws Exception {assertEquals(CODE,decode(qr(CODE,4,0xffffffff,0xff000000)));}
    @Test public void croppedJpegRoundTripIsSupported() throws Exception {assertEquals(CODE,decode(roundTrip(qr(CODE,5,0xff000000,0xffffffff),"jpg")));}
    @Test public void rightAngleRotationsOfTightCropAreSupported() throws Exception {
        BufferedImage image=qr(CODE,3,0xff000000,0xffffffff);
        for(int i=0;i<4;i++){
            assertEquals(CODE,decode(image));BufferedImage rotated=new BufferedImage(image.getHeight(),image.getWidth(),BufferedImage.TYPE_INT_ARGB);
            for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++)rotated.setRGB(image.getHeight()-1-y,x,image.getRGB(x,y));
            image=rotated;
        }
    }
    @Test public void phoneScreenshotAndWideImageBothReadExactPayload() throws Exception {
        BufferedImage code=qr(CODE,3,0xff000000,0xffffffff);
        for(int[] size:new int[][]{{540,1000},{700,210},{230,500}}) {
            BufferedImage image=new BufferedImage(size[0],size[1],BufferedImage.TYPE_INT_ARGB);
            int[] background=new int[size[0]*size[1]];Arrays.fill(background,0xffeeeeee);image.setRGB(0,0,size[0],size[1],background,0,size[0]);
            image.setRGB((size[0]-code.getWidth())/2,(size[1]-code.getHeight())/2,code.getWidth(),code.getHeight(),pixels(code),0,code.getWidth());
            for(int x=10;x<80;x++)for(int y=10;y<14;y++)image.setRGB(x,y,0xff222222);
            assertEquals(CODE,decode(image));
        }
    }
    @Test public void grayLowContrastQrStillReads() throws Exception {assertEquals(CODE,decode(qr(CODE,5,0xff777777,0xffcccccc)));}
    @Test public void decoderDoesNotChangeQrContentOrInputPixels() throws Exception {
        String text="LPA:1$example.test$Case-SENSITIVE+$";BufferedImage image=qr(text,3,0xff000000,0xffffffff);
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
}
