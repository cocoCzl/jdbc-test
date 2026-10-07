package com.jdbctest.extension;

import java.util.ArrayList;
import java.util.List;

class SqlSplitter {

    static class State {
        boolean inString;
        boolean inLineComment;
        boolean inBlockComment;
        boolean inDollarQuote;
        char stringChar;
        String dollarTag;
        int beginDepth;
    }

    interface Config {
        boolean isBatchSeparator(int i, char c, char next, State ctx, CharSequence sql);
        default int skipAfterMatch() { return 0; }
    }

    static String[] split(CharSequence content, Config config) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        State ctx = new State();
        StringBuilder lastWord = new StringBuilder();

        int len = content.length();
        for (int i = 0; i < len; i++) {
            char c = content.charAt(i);
            char next = i + 1 < len ? content.charAt(i + 1) : 0;

            if (ctx.inDollarQuote) {
                current.append(c);
                if (c == '$' && ctx.dollarTag != null) {
                    int tagStart = i - ctx.dollarTag.length();
                    if (tagStart >= 0) {
                        boolean match = true;
                        for (int j = 0; j < ctx.dollarTag.length(); j++) {
                            if (content.charAt(tagStart + j) != ctx.dollarTag.charAt(j)) {
                                match = false;
                                break;
                            }
                        }
                        if (match) {
                            ctx.inDollarQuote = false;
                            ctx.dollarTag = null;
                        }
                    }
                }
                continue;
            }

            if (ctx.inLineComment) {
                if (c == '\n') { ctx.inLineComment = false; lastWord.setLength(0); }
                current.append(c);
                continue;
            }
            if (ctx.inBlockComment) {
                if (c == '*' && next == '/') {
                    ctx.inBlockComment = false;
                    current.append(c).append(next);
                    i++;
                } else {
                    current.append(c);
                }
                continue;
            }
            if (ctx.inString) {
                current.append(c);
                if (c == ctx.stringChar && (i == 0 || content.charAt(i - 1) != '\\')) {
                    ctx.inString = false;
                }
                continue;
            }

            if (c == '-' && next == '-') { ctx.inLineComment = true; current.append(c); lastWord.setLength(0); continue; }
            if (c == '/' && next == '*') { ctx.inBlockComment = true; current.append(c); continue; }
            if (c == '\'' || c == '"') { ctx.inString = true; ctx.stringChar = c; current.append(c); lastWord.setLength(0); continue; }

            if (c == '$') {
                int end = indexOf(content, '$', i + 1);
                if (end > i) {
                    String tag = content.subSequence(i + 1, end).toString();
                    ctx.dollarTag = tag;
                    ctx.inDollarQuote = true;
                    current.append(c).append(tag).append('$');
                    i = end;
                    lastWord.setLength(0);
                    continue;
                }
            }

            if (Character.isWhitespace(c) || c == '(' || c == ')' || c == ';') {
                if (equalsIgnoreCase(lastWord, "BEGIN")) ctx.beginDepth++;
                if (equalsIgnoreCase(lastWord, "END")) {
                    if (ctx.beginDepth > 0) ctx.beginDepth--;
                }
                if (c == ';' || !Character.isLetterOrDigit(c) || c == '(' || c == ')') {
                    lastWord.setLength(0);
                }
            } else if (Character.isLetter(c)) {
                lastWord.append(c);
            } else {
                lastWord.setLength(0);
            }

            if (config.isBatchSeparator(i, c, next, ctx, content)) {
                String stmt = current.toString().trim();
                if (!stmt.isEmpty()) {
                    statements.add(stmt);
                }
                current.setLength(0);
                lastWord.setLength(0);
                i += config.skipAfterMatch();
            } else {
                current.append(c);
            }
        }

        String last = current.toString().trim();
        if (!last.isEmpty()) {
            statements.add(last);
        }

        return statements.toArray(new String[0]);
    }

    private static int indexOf(CharSequence cs, char ch, int from) {
        for (int i = from; i < cs.length(); i++) {
            if (cs.charAt(i) == ch) return i;
        }
        return -1;
    }

    private static boolean equalsIgnoreCase(StringBuilder sb, String s) {
        if (sb.length() != s.length()) return false;
        for (int i = 0; i < sb.length(); i++) {
            char a = sb.charAt(i);
            char b = s.charAt(i);
            if (a != b && Character.toUpperCase(a) != Character.toUpperCase(b)) return false;
        }
        return true;
    }
}
