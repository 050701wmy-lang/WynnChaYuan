package com.wynnchayuan.ai;

import java.util.List;
import java.util.regex.Pattern;

public final class PlaceholderValidator {
    private static final Pattern TOKENS = Pattern.compile(
            "\\{[^{}\\r\\n]+}|<[^<>\\r\\n]+>|%(?:\\d+\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?[a-zA-Z%]|§(?:#[0-9a-fA-F]{6}|.)");
    private static final Pattern MARKDOWN = Pattern.compile(
            "(?m)^\\s*(?:#{1,6} |[-*+] |[0-9]+\\. |>|```)|\\*\\*|__|\\[[^]\\n]+]\\([^\\n)]+\\)");
    private PlaceholderValidator() {}

    static String withoutTokens(String source) {
        return TOKENS.matcher(source).replaceAll("");
    }

    public static boolean valid(String source, String translation) {
        if (source == null || source.isBlank() || translation == null || translation.isBlank()
                || source.length() > 4000 || translation.length() > 16000
                || translation.contains("```") || translation.indexOf('\0') >= 0) return false;
        // Existing list syntax is allowed, but adding Markdown is not.
        if (MARKDOWN.matcher(translation).find() && !MARKDOWN.matcher(source).find()) return false;
        if (wrapped(translation) && !wrapped(source)) return false;
        if (translation.chars().filter(c -> c == '\n').count()
                != source.chars().filter(c -> c == '\n').count()) return false;
        return tokens(source).equals(tokens(translation));
    }

    private static boolean wrapped(String text) {
        return text.length() >= 2 && ((text.startsWith("\"") && text.endsWith("\""))
                || (text.startsWith("“") && text.endsWith("”"))
                || (text.startsWith("`") && text.endsWith("`")));
    }

    private static List<String> tokens(String text) {
        return TOKENS.matcher(text).results().map(m -> m.group()).toList();
    }
}
