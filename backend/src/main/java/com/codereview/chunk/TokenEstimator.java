package com.codereview.chunk;

/**
 * token 估算：字符数 / 3.5（向上取整），保守高估以留余量。
 */
public final class TokenEstimator {

    private static final double CHARS_PER_TOKEN = 3.5;

    private TokenEstimator() {
    }

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) Math.ceil(text.length() / CHARS_PER_TOKEN);
    }
}
