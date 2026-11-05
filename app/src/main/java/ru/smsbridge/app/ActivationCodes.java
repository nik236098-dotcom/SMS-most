package ru.smsbridge.app;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Operator text and QR payloads → LPA:1$ codes. No Android and no network. */
public final class ActivationCodes {
    public static final class Item {
        public final String number;
        public final String code;
        public Item(String number, String code) { this.number = number; this.code = code; }
    }
    // SM-DP+ address, then a matching id. Stops at whitespace and URL delimiters.
    private static final Pattern CODE = Pattern.compile("(?i)(?:LPA:)?1\\$[A-Za-z0-9._-]{3,}\\$[^\\s&\"'<>]{4,}");
    private ActivationCodes() {}

    public static List<Item> parse(String raw) {
        List<Item> out = new ArrayList<>();
        if (raw == null) return out;
        String text = raw.replace("\uFEFF", "").replace("\u200B", "").replace("\u200C", "").replace("\u200D", "").trim();
        if (text.indexOf('%') >= 0) {
            try { text = URLDecoder.decode(text, StandardCharsets.UTF_8.name()); }
            catch (Exception ignored) { /* Keep the original payload. */ }
        }
        String pending = "";
        for (String line : text.split("\\r?\\n")) {
            String chunk = line.trim();
            if (chunk.isEmpty()) continue;
            Matcher matcher = CODE.matcher(chunk);
            int found = 0;
            while (matcher.find()) {
                String code = canonical(matcher.group());
                if (code == null) continue;
                String phone = "";
                if (found == 0) phone = phoneOf(chunk.substring(0, matcher.start()));
                if (found == 0 && phone.isEmpty()) phone = pending;
                pending = "";
                out.add(new Item(phone, code));
                found++;
            }
            if (found == 0) {
                String phone = phoneOf(chunk);
                if (!phone.isEmpty()) pending = phone;
            }
        }
        return dedupe(out);
    }

    private static String canonical(String token) {
        String code = token.trim().replaceAll("[.,);]+$", "");
        if (code.toLowerCase(Locale.ROOT).startsWith("lpa:")) code = "LPA:" + code.substring(4);
        else code = "LPA:" + code;
        if (!code.startsWith("LPA:1$") || code.length() > 2048) return null;
        String[] parts = code.split("\\$", -1);
        if (parts.length < 3 || parts[1].isEmpty() || parts[2].isEmpty()) return null;
        return code;
    }

    private static String phoneOf(String head) {
        if (head == null) return "";
        String trimmed = head.trim();
        if (trimmed.isEmpty()) return "";
        String[] bits = trimmed.split("[\\s,;|]+");
        for (int i = bits.length - 1; i >= 0; i--) {
            try { return Rules.phone(bits[i]); }
            catch (RuntimeException ignored) { /* Not a phone token. */ }
        }
        try { return Rules.phone(trimmed); }
        catch (RuntimeException ignored) { return ""; }
    }

    private static List<Item> dedupe(List<Item> in) {
        List<Item> out = new ArrayList<>();
        for (Item item : in) {
            boolean seen = false;
            for (Item have : out) if (have.code.equals(item.code)) { seen = true; break; }
            if (!seen) out.add(item);
        }
        return out;
    }
}
