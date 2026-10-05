package ru.smsbridge.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pure Java rules; tested without the Android SDK. */
public final class Rules {
    private Rules() {}
    public static long telegramId(String text) {
        if(text==null || !text.trim().matches("[1-9][0-9]{0,15}"))
            throw new IllegalArgumentException("Telegram ID — положительное число, без @ и без номера телефона");
        long value=Long.parseLong(text.trim());
        if(value>4503599627370495L)throw new IllegalArgumentException("Проверь Telegram ID: число слишком большое");
        return value;
    }
    public static List<Long> telegramIds(String text) {
        java.util.LinkedHashSet<Long> unique=new java.util.LinkedHashSet<>();
        if(text!=null && !text.trim().isEmpty())for(String part:text.trim().split("[,;\\s]+"))unique.add(telegramId(part));
        if(unique.size()>10)throw new IllegalArgumentException("Можно указать до 10 Telegram ID");
        return new ArrayList<>(unique);
    }
    public static String joinIds(List<Long> ids) {
        StringBuilder out=new StringBuilder();for(long id:ids){if(out.length()>0)out.append(", ");out.append(id);}return out.toString();
    }
    public static String phone(String value) {
        String p = value == null ? "" : value.replaceAll("[\\s()\\-]", "");
        if (!p.matches("\\+[1-9][0-9]{6,14}")) throw new IllegalArgumentException("Введите номер с кодом страны, например +79991234567");
        return p;
    }
    public static String service(String sender) {
        if (sender == null || sender.isEmpty()) return "Неизвестный отправитель";
        // Alphanumeric sender is the identity supplied by the network, not verified branding.
        String s = sender.trim();
        if (s.equals("900")) return "Сбер (по отправителю 900)";
        if (s.equalsIgnoreCase("beeline")) return "Билайн";
        if (s.equalsIgnoreCase("telegram")) return "Telegram";
        return s;
    }
    public static List<String> chunks(String text) {
        List<String> out = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + 3700, text.length());
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
            out.add(text.substring(start, end)); start = end;
        }
        if (out.isEmpty()) out.add("");
        return out;
    }
    public static long retryMillis(int attempts, long seconds) {
        if (seconds > 0) return Math.min(seconds, Long.MAX_VALUE / 2000L) * 1000L;
        return Math.min(15000L, 2000L * (1L << Math.min(Math.max(attempts, 0), 3)));
    }
    public static String hash(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255));
            return out.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static String profileKey(String eid, String iccid) {
        if (eid == null || !eid.matches("[0-9]{20,40}") || iccid == null || !iccid.matches("[0-9]{10,25}"))
            throw new IllegalArgumentException("Адаптер не вернул корректный идентификатор профиля");
        return eid + ":" + iccid;
    }
    public static boolean authorized(long configuredChat, long chat, String type, boolean isBot) {
        return configuredChat > 0 && configuredChat == chat && "private".equals(type) && !isBot;
    }
    public static boolean authorized(List<Long> ids, long chat, String type, boolean isBot) {
        return chat>0 && ids.contains(chat) && "private".equals(type) && !isBot;
    }
}
