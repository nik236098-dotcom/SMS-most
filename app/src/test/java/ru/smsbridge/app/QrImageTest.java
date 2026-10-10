package ru.smsbridge.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class QrImageTest {
    @Test public void squarePngPixelsReachDecoderWithAlphaAndBitmapIsRecycled() throws Exception {
        QrDecoderTest.Picture image=QrDecoderTest.qr(QrDecoderTest.CODE,3,0xff000000,0x00000000);
        Bitmap bitmap=mock(Bitmap.class);when(bitmap.getWidth()).thenReturn(image.getWidth());when(bitmap.getHeight()).thenReturn(image.getHeight());
        doAnswer(i->{System.arraycopy(QrDecoderTest.pixels(image),0,i.getArgument(0),0,image.getWidth()*image.getHeight());return null;})
            .when(bitmap).getPixels(any(int[].class),anyInt(),anyInt(),anyInt(),anyInt(),anyInt(),anyInt());
        byte[] raw={1,2,3};
        try(MockedConstruction<BitmapFactory.Options> options=mockConstruction(BitmapFactory.Options.class);MockedStatic<BitmapFactory> factory=mockStatic(BitmapFactory.class)) {
            factory.when(()->BitmapFactory.decodeByteArray(eq(raw),eq(0),eq(raw.length),any())).thenAnswer(i->{
                BitmapFactory.Options o=i.getArgument(3);if(o.inJustDecodeBounds){o.outWidth=image.getWidth();o.outHeight=image.getHeight();return null;}return bitmap;
            });
            assertEquals(QrDecoderTest.CODE,QrImage.decode(raw));verify(bitmap).recycle();
        }
    }
    @Test public void oversizedImageUsesBoundedSamplingAndRecyclesOnUnreadableQr() throws Exception {
        Bitmap bitmap=mock(Bitmap.class);when(bitmap.getWidth()).thenReturn(1250);when(bitmap.getHeight()).thenReturn(250);
        byte[] raw={1};List<Integer> samples=new ArrayList<>();
        try(MockedConstruction<BitmapFactory.Options> options=mockConstruction(BitmapFactory.Options.class);MockedStatic<BitmapFactory> factory=mockStatic(BitmapFactory.class)) {
            factory.when(()->BitmapFactory.decodeByteArray(eq(raw),eq(0),eq(1),any())).thenAnswer(i->{
                BitmapFactory.Options o=i.getArgument(3);if(o.inJustDecodeBounds){o.outWidth=5000;o.outHeight=1000;return null;}samples.add(o.inSampleSize);return bitmap;
            });
            assertThrows(UserError.class,()->QrImage.decode(raw));assertEquals(java.util.Collections.singletonList(4),samples);verify(bitmap).recycle();
        }
    }
    @Test public void emptyOrUnsupportedImageHasSpecificError() {
        assertThrows(UserError.class,()->QrImage.decode(new byte[0]));
        try(MockedConstruction<BitmapFactory.Options> options=mockConstruction(BitmapFactory.Options.class);MockedStatic<BitmapFactory> factory=mockStatic(BitmapFactory.class)) {
            UserError error=assertThrows(UserError.class,()->QrImage.decode(new byte[]{1,2}));assertTrue(error.getMessage().contains("PNG/JPG/WebP"));
        }
    }
}
