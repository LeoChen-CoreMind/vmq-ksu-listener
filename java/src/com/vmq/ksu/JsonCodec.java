package com.vmq.ksu;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class JsonCodec {
    private JsonCodec() {
    }

    static Map<String, Object> parseObject(String json) {
        Object value = parse(json);
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("JSON 根节点必须是对象");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> object = (Map<String, Object>) value;
        return object;
    }

    static Object parse(String json) {
        return new Parser(json).parse();
    }

    static String stringify(Object value) {
        StringBuilder output = new StringBuilder();
        append(output, value);
        return output.toString();
    }

    private static void append(StringBuilder output, Object value) {
        if (value == null) {
            output.append("null");
        } else if (value instanceof String) {
            appendString(output, (String) value);
        } else if (value instanceof Number || value instanceof Boolean) {
            output.append(value.toString());
        } else if (value instanceof Map) {
            output.append('{');
            boolean first = true;
            for (Object rawEntry : ((Map<?, ?>) value).entrySet()) {
                Map.Entry<?, ?> entry = (Map.Entry<?, ?>) rawEntry;
                if (!first) {
                    output.append(',');
                }
                first = false;
                appendString(output, String.valueOf(entry.getKey()));
                output.append(':');
                append(output, entry.getValue());
            }
            output.append('}');
        } else if (value instanceof Iterable) {
            output.append('[');
            boolean first = true;
            for (Object item : (Iterable<?>) value) {
                if (!first) {
                    output.append(',');
                }
                first = false;
                append(output, item);
            }
            output.append(']');
        } else {
            throw new IllegalArgumentException("不支持的 JSON 值：" + value.getClass().getName());
        }
    }

    private static void appendString(StringBuilder output, String value) {
        output.append('"');
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '"': output.append("\\\""); break;
                case '\\': output.append("\\\\"); break;
                case '\b': output.append("\\b"); break;
                case '\f': output.append("\\f"); break;
                case '\n': output.append("\\n"); break;
                case '\r': output.append("\\r"); break;
                case '\t': output.append("\\t"); break;
                default:
                    if (current < 0x20) {
                        output.append(String.format("\\u%04x", (int) current));
                    } else {
                        output.append(current);
                    }
            }
        }
        output.append('"');
    }

    private static final class Parser {
        private final String input;
        private int position;

        Parser(String input) {
            if (input == null) {
                throw new IllegalArgumentException("JSON 不能为空");
            }
            this.input = input;
        }

        Object parse() {
            skipWhitespace();
            Object value = parseValue();
            skipWhitespace();
            if (position != input.length()) {
                fail("存在多余的尾部数据");
            }
            return value;
        }

        private Object parseValue() {
            if (position >= input.length()) {
                fail("输入意外结束");
            }
            char current = input.charAt(position);
            if (current == '{') return parseObjectValue();
            if (current == '[') return parseArray();
            if (current == '"') return parseString();
            if (current == 't') return literal("true", Boolean.TRUE);
            if (current == 'f') return literal("false", Boolean.FALSE);
            if (current == 'n') return literal("null", null);
            if (current == '-' || Character.isDigit(current)) return parseNumber();
            fail("出现意外字符");
            return null;
        }

        private Map<String, Object> parseObjectValue() {
            LinkedHashMap<String, Object> object = new LinkedHashMap<String, Object>();
            position++;
            skipWhitespace();
            if (consume('}')) return object;
            while (true) {
                skipWhitespace();
                if (position >= input.length() || input.charAt(position) != '"') fail("对象键必须是字符串");
                String key = parseString();
                skipWhitespace();
                require(':');
                skipWhitespace();
                object.put(key, parseValue());
                skipWhitespace();
                if (consume('}')) return object;
                require(',');
            }
        }

        private List<Object> parseArray() {
            ArrayList<Object> array = new ArrayList<Object>();
            position++;
            skipWhitespace();
            if (consume(']')) return array;
            while (true) {
                skipWhitespace();
                array.add(parseValue());
                skipWhitespace();
                if (consume(']')) return array;
                require(',');
            }
        }

        private String parseString() {
            require('"');
            StringBuilder value = new StringBuilder();
            while (position < input.length()) {
                char current = input.charAt(position++);
                if (current == '"') return value.toString();
                if (current != '\\') {
                    value.append(current);
                    continue;
                }
                if (position >= input.length()) fail("转义序列未结束");
                char escaped = input.charAt(position++);
                switch (escaped) {
                    case '"': value.append('"'); break;
                    case '\\': value.append('\\'); break;
                    case '/': value.append('/'); break;
                    case 'b': value.append('\b'); break;
                    case 'f': value.append('\f'); break;
                    case 'n': value.append('\n'); break;
                    case 'r': value.append('\r'); break;
                    case 't': value.append('\t'); break;
                    case 'u': value.append(parseUnicode()); break;
                    default: fail("无效转义序列");
                }
            }
            fail("字符串未结束");
            return null;
        }

        private char parseUnicode() {
            if (position + 4 > input.length()) fail("无效 Unicode 转义序列");
            try {
                char value = (char) Integer.parseInt(input.substring(position, position + 4), 16);
                position += 4;
                return value;
            } catch (NumberFormatException exception) {
                fail("无效 Unicode 转义序列");
                return 0;
            }
        }

        private Number parseNumber() {
            int start = position;
            if (input.charAt(position) == '-') position++;
            while (position < input.length() && Character.isDigit(input.charAt(position))) position++;
            boolean decimal = false;
            if (position < input.length() && input.charAt(position) == '.') {
                decimal = true;
                position++;
                while (position < input.length() && Character.isDigit(input.charAt(position))) position++;
            }
            if (position < input.length() && (input.charAt(position) == 'e' || input.charAt(position) == 'E')) {
                decimal = true;
                position++;
                if (position < input.length() && (input.charAt(position) == '+' || input.charAt(position) == '-')) position++;
                while (position < input.length() && Character.isDigit(input.charAt(position))) position++;
            }
            String token = input.substring(start, position);
            try {
                if (decimal) {
                    return Double.valueOf(token);
                }
                return Long.valueOf(token);
            } catch (NumberFormatException exception) {
                fail("无效数字");
                return null;
            }
        }

        private Object literal(String token, Object value) {
            if (!input.startsWith(token, position)) fail("无效字面量");
            position += token.length();
            return value;
        }

        private boolean consume(char expected) {
            if (position < input.length() && input.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        private void require(char expected) {
            if (!consume(expected)) fail("应为 '" + expected + "'");
        }

        private void skipWhitespace() {
            while (position < input.length() && Character.isWhitespace(input.charAt(position))) position++;
        }

        private void fail(String message) {
            throw new IllegalArgumentException(message + "，位置 " + position);
        }
    }
}
