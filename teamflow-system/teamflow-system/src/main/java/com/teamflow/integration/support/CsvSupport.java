package com.teamflow.integration.support;

import java.util.ArrayList;
import java.util.List;

/**
 * CSV 读写支撑（2.4.5）：UTF-8 + BOM，兼容 Excel；解析支持引号、逗号与换行转义。
 */
public final class CsvSupport {

    /** UTF-8 BOM：Excel 打开中文 CSV 不乱码的关键。 */
    public static final String BOM = "\uFEFF";

    private CsvSupport() {
    }

    /** 拼一行 CSV（字段按需加引号转义）。 */
    public static String row(Object... values) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < values.length; index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append(escape(values[index] == null ? "" : String.valueOf(values[index])));
        }
        return builder.toString();
    }

    /** 字段转义：含逗号/引号/换行时用双引号包裹，内部双引号翻倍。 */
    public static String escape(String value) {
        if (value == null) {
            return "";
        }
        boolean needsQuote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!needsQuote) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    /** 去除 BOM（导入文件可能带 BOM）。 */
    public static String stripBom(String text) {
        if (text == null) {
            return null;
        }
        return text.startsWith(BOM) ? text.substring(BOM.length()) : text;
    }

    /**
     * 解析 CSV 文本为行列表（每行字段列表）。
     *
     * <p>支持：引号包裹的字段、字段内逗号/换行、双引号转义（""）；空行自动跳过。</p>
     */
    public static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return rows;
        }
        String content = stripBom(text);
        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < content.length(); index++) {
            char ch = content.charAt(index);
            if (quoted) {
                if (ch == '"') {
                    if (index + 1 < content.length() && content.charAt(index + 1) == '"') {
                        field.append('"');
                        index++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(ch);
                }
                continue;
            }
            switch (ch) {
                case '"' -> quoted = true;
                case ',' -> {
                    current.add(field.toString());
                    field.setLength(0);
                }
                case '\r' -> {
                    // 忽略 CR，等待 LF 或单独 CR 结束行
                    if (index + 1 >= content.length() || content.charAt(index + 1) != '\n') {
                        endRow(rows, current, field);
                    }
                }
                case '\n' -> endRow(rows, current, field);
                default -> field.append(ch);
            }
        }
        if (field.length() > 0 || !current.isEmpty()) {
            current.add(field.toString());
            endRow(rows, current, field);
        }
        return rows;
    }

    private static void endRow(List<List<String>> rows, List<String> current, StringBuilder field) {
        current.add(field.toString());
        field.setLength(0);
        boolean allBlank = current.stream().allMatch(item -> item == null || item.isBlank());
        if (!allBlank) {
            rows.add(new ArrayList<>(current));
        }
        current.clear();
    }
}
