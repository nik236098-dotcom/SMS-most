package ru.smsbridge.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

/** A code authorizes the remote recipient, not the person holding the Android. */
public final class SetupCode {
    private SetupCode() {}
    public static String create() {
        return String.format(Locale.ROOT, "%08d", new SecureRandom().nextInt(100000000));
    }
    public static boolean matches(String expected, String supplied, long expires, long now) {
        return expected != null && expected.matches("[0-9]{8}") && supplied != null
            && supplied.matches("[0-9]{8}") && expires > now
            && MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), supplied.getBytes(StandardCharsets.US_ASCII));
    }
}
