package io.usblink.mobile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small bounded JSON codec shared by the Android daemon and its JVM tests. */
public final class Json {
    private Json() {}
    public static Map<String, Object> map(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) out.put((String) pairs[i], pairs[i + 1]);
        return out;
    }
    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value) {
        if (!(value instanceof Map)) throw new IllegalArgumentException("需要 JSON 对象");
        return (Map<String, Object>) value;
    }
    public static String string(Map<String, Object> object, String key) {
        Object value = object.get(key);
        if (!(value instanceof String)) throw new IllegalArgumentException("缺少有效字段：" + key);
        return (String) value;
    }
    public static int integer(Map<String, Object> object, String key) {
        Object value = object.get(key);
        if (!(value instanceof Long) && !(value instanceof Integer)) throw new IllegalArgumentException("需要整数：" + key);
        long n = ((Number) value).longValue();
        if (n < Integer.MIN_VALUE || n > Integer.MAX_VALUE) throw new IllegalArgumentException("整数超出范围");
        return (int) n;
    }
    public static Object parse(String text) {
        if (text == null || text.length() > 65536) throw new IllegalArgumentException("JSON 超出大小限制");
        Parser p = new Parser(text);
        Object value = p.value(0);
        p.space();
        if (p.i != text.length()) throw new IllegalArgumentException("JSON 尾部有多余内容");
        return value;
    }
    public static String stringify(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value, 0);
        return out.toString();
    }
    private static void write(StringBuilder out, Object value, int depth) {
        if (depth > 20) throw new IllegalArgumentException("JSON 嵌套过深");
        if (value == null) { out.append("null"); return; }
        if (value instanceof String) {
            out.append('"');
            for (char c : ((String) value).toCharArray()) {
                switch (c) {
                    case '"': out.append("\\\""); break;
                    case '\\': out.append("\\\\"); break;
                    case '\n': out.append("\\n"); break;
                    case '\r': out.append("\\r"); break;
                    case '\t': out.append("\\t"); break;
                    default: if (c < 32) out.append(String.format("\\u%04x", (int)c)); else out.append(c);
                }
            }
            out.append('"');
        } else if (value instanceof Boolean || value instanceof Number) {
            if (value instanceof Double && !Double.isFinite((Double)value)) throw new IllegalArgumentException("非法数字");
            out.append(value);
        } else if (value instanceof Map) {
            out.append('{'); boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) {
                if (!first) out.append(','); first = false;
                write(out, e.getKey(), depth + 1); out.append(':'); write(out, e.getValue(), depth + 1);
            }
            out.append('}');
        } else if (value instanceof Iterable) {
            out.append('['); boolean first = true;
            for (Object item : (Iterable<?>)value) {
                if (!first) out.append(','); first = false; write(out, item, depth + 1);
            }
            out.append(']');
        } else throw new IllegalArgumentException("不支持的 JSON 类型");
    }
    private static final class Parser {
        final String s; int i;
        Parser(String s) { this.s = s; }
        void space() { while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) i++; }
        boolean take(char c) { space(); if (i < s.length() && s.charAt(i) == c) { i++; return true; } return false; }
        void need(char c) { if (!take(c)) throw bad(); }
        IllegalArgumentException bad() { return new IllegalArgumentException("JSON 格式错误"); }
        Object value(int depth) {
            if (depth > 16) throw bad(); space(); if (i >= s.length()) throw bad();
            char c = s.charAt(i);
            if (c == '"') return text();
            if (c == '{') {
                i++; Map<String,Object> out = new LinkedHashMap<>();
                if (take('}')) return out;
                do { space(); String key = text(); need(':'); if (out.containsKey(key)) throw bad(); out.put(key, value(depth+1)); } while (take(','));
                need('}'); return out;
            }
            if (c == '[') {
                i++; List<Object> out = new ArrayList<>(); if (take(']')) return out;
                do { out.add(value(depth+1)); if (out.size() > 2048) throw bad(); } while (take(',')); need(']'); return out;
            }
            for (String literal : new String[]{"true", "false", "null"}) {
                if (s.startsWith(literal, i)) { i += literal.length(); return literal.equals("null") ? null : literal.equals("true"); }
            }
            int start = i; if (s.charAt(i) == '-') i++;
            if (i >= s.length() || !Character.isDigit(s.charAt(i))) throw bad();
            if (s.charAt(i) == '0') i++; else while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            boolean decimal = false;
            if (i < s.length() && s.charAt(i) == '.') { decimal = true; i++; int j=i; while (i<s.length() && Character.isDigit(s.charAt(i))) i++; if (i==j) throw bad(); }
            if (i<s.length() && (s.charAt(i)=='e' || s.charAt(i)=='E')) { decimal=true; i++; if (i<s.length() && (s.charAt(i)=='+' || s.charAt(i)=='-')) i++; int j=i; while(i<s.length() && Character.isDigit(s.charAt(i))) i++; if(i==j) throw bad(); }
            try { if (decimal) { double n=Double.parseDouble(s.substring(start,i)); if(!Double.isFinite(n))throw bad(); return n; } return Long.parseLong(s.substring(start,i)); } catch(NumberFormatException e) { throw bad(); }
        }
        String text() {
            if (i >= s.length() || s.charAt(i++) != '"') throw bad(); StringBuilder out = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++); if (c == '"') return out.toString(); if (c < 32) throw bad();
                if (c == '\\') {
                    if (i>=s.length()) throw bad(); char esc=s.charAt(i++);
                    switch(esc) {
                        case '"': case '\\': case '/': c=esc; break;
                        case 'b': c='\b'; break; case 'f': c='\f'; break; case 'n': c='\n'; break; case 'r': c='\r'; break; case 't': c='\t'; break;
                        case 'u': if(i+4>s.length())throw bad(); try{ c=(char)Integer.parseInt(s.substring(i,i+4),16); }catch(NumberFormatException e){throw bad();} i+=4; break;
                        default: throw bad();
                    }
                }
                out.append(c);
            }
            throw bad();
        }
    }
}
