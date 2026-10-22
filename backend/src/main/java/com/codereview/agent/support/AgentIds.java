package com.codereview.agent.support;

import java.security.SecureRandom;

/**
 * 服务端生成的 26 位 ULID。
 *
 * <p>选型理由：字符串 ID 不把数据库大整数暴露给 JSON；ULID 前 48 位是毫秒时间戳，
 * 同一毫秒内按生成顺序近似可比，正好配合 {@code (created_at, id)} 游标排序。
 * 大小写不敏感，统一用大写。
 */
public final class AgentIds {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int LENGTH = 26;

    private AgentIds() {
    }

    public static String next() {
        byte[] data = new byte[16];
        long millis = System.currentTimeMillis();
        for (int i = 5; i >= 0; i--) {
            data[i] = (byte) (millis & 0xFF);
            millis >>>= 8;
        }
        byte[] random = new byte[10];
        RANDOM.nextBytes(random);
        System.arraycopy(random, 0, data, 6, 10);
        return encode(data);
    }

    static String encode(byte[] data) {
        char[] out = new char[LENGTH];
        for (int i = 0; i < LENGTH; i++) {
            int value = 0;
            for (int j = 0; j < 5; j++) {
                int bit = i * 5 + j - 2;
                value <<= 1;
                if (bit >= 0) {
                    int index = bit / 8;
                    int offset = 7 - (bit % 8);
                    value |= (data[index] >> offset) & 1;
                }
            }
            out[i] = ALPHABET[value];
        }
        return new String(out);
    }

    /** 只校验形状，用于入口快速拒绝客户端自造 ID。 */
    public static boolean isValid(String id) {
        if (id == null || id.length() != LENGTH) {
            return false;
        }
        for (int i = 0; i < LENGTH; i++) {
            char c = id.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z' && c != 'I' && c != 'L' && c != 'O' && c != 'U');
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    public static String requireValid(String id, String field) {
        if (!isValid(id)) {
            throw new IllegalArgumentException(field + " 不是合法的稳定 ID：" + id);
        }
        return id;
    }
}
