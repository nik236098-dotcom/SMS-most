package ru.smsbridge.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

/** Android image decoding; retain aspect ratio and alpha for the QR decoder. */
final class QrImage {
    static String decode(byte[] raw) throws UserError {
        if(raw==null||raw.length==0)throw new UserError("Файл изображения пуст.");
        BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(raw,0,raw.length,options);
        if(options.outWidth<=0||options.outHeight<=0)throw new UserError("Не удалось открыть изображение. Пришли QR как фото или файл PNG/JPG/WebP, либо строку LPA:1$…");
        options.inSampleSize=1;
        while((Math.max(options.outWidth,options.outHeight)+(long)options.inSampleSize-1)/options.inSampleSize>QrDecoder.MAX_EDGE)options.inSampleSize*=2;
        options.inJustDecodeBounds=false;options.inPreferredConfig=Bitmap.Config.ARGB_8888;
        Bitmap bitmap=BitmapFactory.decodeByteArray(raw,0,raw.length,options);
        if(bitmap==null)throw new UserError("Не удалось открыть изображение. Отправь оригинал как файл PNG/JPG/WebP.");
        try {
            int width=bitmap.getWidth(),height=bitmap.getHeight();
            if(width<=0||height<=0||width>QrDecoder.MAX_EDGE||height>QrDecoder.MAX_EDGE)
                throw new UserError("Не удалось уменьшить изображение для чтения QR-кода.");
            int[] pixels=new int[width*height];bitmap.getPixels(pixels,0,width,0,0,width,height);
            return QrDecoder.decode(width,height,pixels);
        }finally{bitmap.recycle();}
    }
}
