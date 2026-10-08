package ru.smsbridge.app;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.json.JSONObject;
import org.junit.Test;
import org.mockito.MockedStatic;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class StoreReadRecoveryTest {
    @Test public void unreadableHeadIsPreservedAndFollowingMessageRemainsSendable() throws Exception {
        Store s=mock(Store.class);SQLiteDatabase db=mock(SQLiteDatabase.class);Cursor bad=mock(Cursor.class),good=mock(Cursor.class);
        when(s.getReadableDatabase()).thenReturn(db);when(s.next()).thenCallRealMethod();
        when(db.rawQuery(anyString(),any())).thenReturn(bad,good);
        when(bad.moveToFirst()).thenReturn(true);when(bad.getLong(0)).thenReturn(40L);when(bad.getString(1)).thenReturn("unreadable");
        when(good.moveToFirst()).thenReturn(true);when(good.getLong(0)).thenReturn(41L);when(good.getString(1)).thenReturn("readable");
        try(MockedStatic<Crypto> crypto=mockStatic(Crypto.class)){
            crypto.when(()->Crypto.open("unreadable")).thenThrow(new IllegalStateException());
            crypto.when(()->Crypto.open("readable")).thenReturn("{\"body\":\"SMS after damaged row\"}");
            JSONObject next=s.next();assertEquals(41,next.getLong("id"));assertEquals("SMS after damaged row",next.getString("body"));
            verify(s).failed(eq(40L),eq(0),eq(0L),contains("сохранено"));verify(s,never()).delivered(40);verify(s,never()).purgeQueue();
        }
    }
    @Test public void manyUnreadableRowsYieldInsteadOfLockingStoreForever() throws Exception {
        Store s=mock(Store.class);SQLiteDatabase db=mock(SQLiteDatabase.class);Cursor bad=mock(Cursor.class);
        when(s.getReadableDatabase()).thenReturn(db);when(s.next()).thenCallRealMethod();when(db.rawQuery(anyString(),any())).thenReturn(bad);
        when(bad.moveToFirst()).thenReturn(true);when(bad.getString(1)).thenReturn("unreadable");
        try(MockedStatic<Crypto> crypto=mockStatic(Crypto.class)){
            crypto.when(()->Crypto.open("unreadable")).thenThrow(new IllegalStateException());assertNull(s.next());
            verify(s,times(10)).failed(anyLong(),anyInt(),eq(0L),anyString());
        }
    }
}
