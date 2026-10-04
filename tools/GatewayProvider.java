package ru.smsbridge.adapter;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import java.util.Set;

/** Only the private signing certificate of the paired relay APK may invoke the LPA. */
public final class GatewayProvider extends ContentProvider {
    private static final String RELAY="ru.smsbridge.app";
    private static final String RELAY_SHA256="@@RELAY_SHA256@@";
    private final Set<String> actions=new java.util.HashSet<>(java.util.Arrays.asList("cards","profiles","downloadProfile","enableProfile","setPreference"));
    private ContentProvider delegate;
    @Override public boolean onCreate(){return true;}
    private void authenticate() {
        String[] names=getContext().getPackageManager().getPackagesForUid(Binder.getCallingUid());boolean found=false;
        if(names!=null)for(String name:names)if(name.equals(RELAY))found=true;
        byte[] certificate=new byte[RELAY_SHA256.length()/2];for(int i=0;i<certificate.length;i++)certificate[i]=(byte)Integer.parseInt(RELAY_SHA256.substring(2*i,2*i+2),16);
        if(!found||!getContext().getPackageManager().hasSigningCertificate(RELAY,certificate,PackageManager.CERT_INPUT_SHA256))throw new SecurityException("Untrusted caller");
    }
    private ContentProvider delegate() {
        if(delegate==null)try {
            delegate=(ContentProvider)Class.forName("im.angry.openeuicc.bridge.LpaProvider").getDeclaredConstructor().newInstance();
            ProviderInfo info=new ProviderInfo();info.authority="lpa";info.exported=false;info.name=delegate.getClass().getName();
            delegate.attachInfo(getContext(),info);
        } catch(Exception e){throw new IllegalStateException("LPA initialization failed");}return delegate;
    }
    @Override public synchronized Cursor query(Uri uri,String[] projection,String selection,String[] selectionArgs,String sortOrder) {
        authenticate();String action=uri.getLastPathSegment();
        if(!actions.contains(action))throw new SecurityException("Unsupported operation");
        if(uri.getQueryParameter("callbackUrl")!=null)throw new SecurityException("Callbacks disabled");
        if(action.equals("setPreference") && !("ignoreTlsCertificate".equals(uri.getQueryParameter("name"))&&"false".equals(uri.getQueryParameter("enabled"))))throw new SecurityException("TLS verification cannot be disabled");
        long identity=Binder.clearCallingIdentity();
        try {return delegate().query(uri.buildUpon().authority("lpa").build(),projection,selection,selectionArgs,sortOrder);}
        finally {Binder.restoreCallingIdentity(identity);}
    }
    @Override public String getType(Uri uri){return "application/json";}
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException();}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException();}
}
