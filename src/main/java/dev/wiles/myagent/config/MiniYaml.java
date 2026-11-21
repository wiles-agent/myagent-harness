package dev.wiles.myagent.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * frontmatter 里那一小块 YAML：按缩进嵌套的映射和列表、列表项是映射、带不带引号的标量、
 * 行内的 JSON（{@code ["-y", "x"]}）、整行注释。读出来是 {@link JsonNode}，和 settings.json 里的同一种结构。
 *
 * <h3>为什么不引一个 YAML 库</h3>
 * 这个项目的第三方库只有 Jackson、OkHttp、Java-WebSocket 和 slf4j（README 开头那句）。要读的只是 agent 定义里
 * {@code hooks} / {@code mcpServers} 这两段——Claude Code 文档里的写法就这几种结构。锚点、多文档、块标量这些
 * 用不上，遇到读不懂的缩进就<b>抛</b>：调用方据此作废整份定义，不会悄悄读成另一种结构。
 * 一层的 {@code key: value} 仍由 {@code Skill.split} 读（它把续行折成一行，正适合长描述）。
 */
public final class MiniYaml {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final Pattern ENTRY = Pattern.compile("^(\"[^\"]*\"|'[^']*'|[A-Za-z0-9_.@/-]+)\\s*:(?:\\s+(.*))?$");
    private static final Pattern INTEGER = Pattern.compile("-?\\d{1,18}");

    private record Line(int indent, String text, int number) {
    }

    private MiniYaml() {
    }

    /**
     * frontmatter 里顶层字段 {@code key} 的值：没有 frontmatter 或没有这个字段是 null。
     *
     * @throws IllegalArgumentException 缩进对不齐、写成了读不懂的结构
     */
    public static JsonNode frontMatterField(String text, String key) {
        List<Line> lines = frontMatter(text);
        if (lines == null) {
            return null;
        }
        for (int i = 0; i < lines.size(); i++) {
            Line line = lines.get(i);
            if (line.indent() != 0) {
                continue;
            }
            Matcher m = ENTRY.matcher(line.text());
            if (!m.matches() || !key.equals(unquoteKey(m.group(1)))) {
                continue;
            }
            String value = m.group(2) == null ? "" : m.group(2).strip();
            if (!value.isEmpty()) {
                return scalar(value);
            }
            List<Line> block = new ArrayList<>();
            for (int j = i + 1; j < lines.size() && lines.get(j).indent() > 0; j++) {
                block.add(lines.get(j));
            }
            if (block.isEmpty()) {
                return NODES.nullNode();
            }
            int[] pos = {0};
            JsonNode node = block(block, pos, block.get(0).indent());
            if (pos[0] < block.size()) {
                throw malformed(block.get(pos[0]));
            }
            return node;
        }
        return null;
    }

    /** {@code ---} 围起来的那几行，去掉空行和整行注释；没有头是 null。 */
    private static List<Line> frontMatter(String text) {
        String[] raw = text.split("\\R", -1);
        int i = 0;
        while (i < raw.length && raw[i].isBlank()) {
            i++;
        }
        if (i >= raw.length || !"---".equals(raw[i].strip())) {
            return null;
        }
        List<Line> out = new ArrayList<>();
        for (i++; i < raw.length && !"---".equals(raw[i].strip()); i++) {
            String line = raw[i].replace("\t", "    ");
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            out.add(new Line(line.length() - line.stripLeading().length(), trimmed, i + 1));
        }
        return out;
    }

    private static JsonNode block(List<Line> lines, int[] pos, int indent) {
        return isItem(lines.get(pos[0]).text()) ? sequence(lines, pos, indent) : mapping(lines, pos, indent);
    }

    private static ObjectNode mapping(List<Line> lines, int[] pos, int indent) {
        ObjectNode node = NODES.objectNode();
        while (pos[0] < lines.size()) {
            Line line = lines.get(pos[0]);
            if (line.indent() < indent) {
                return node;
            }
            if (line.indent() > indent || isItem(line.text())) {
                throw malformed(line);
            }
            Matcher m = ENTRY.matcher(line.text());
            if (!m.matches()) {
                throw malformed(line);
            }
            String key = unquoteKey(m.group(1));
            String value = m.group(2) == null ? "" : m.group(2).strip();
            pos[0]++;
            if (!value.isEmpty()) {
                node.set(key, scalar(value));
            } else if (pos[0] < lines.size() && lines.get(pos[0]).indent() > indent) {
                node.set(key, block(lines, pos, lines.get(pos[0]).indent()));
            } else if (pos[0] < lines.size() && lines.get(pos[0]).indent() == indent && isItem(lines.get(pos[0]).text())) {
                node.set(key, sequence(lines, pos, indent));     // key:\n- a（列表和键同一列，YAML 允许）
            } else {
                node.set(key, NODES.nullNode());
            }
        }
        return node;
    }

    private static ArrayNode sequence(List<Line> lines, int[] pos, int indent) {
        ArrayNode array = NODES.arrayNode();
        while (pos[0] < lines.size()) {
            Line line = lines.get(pos[0]);
            if (line.indent() < indent || (line.indent() == indent && !isItem(line.text()))) {
                return array;
            }
            if (line.indent() > indent) {
                throw malformed(line);
            }
            String rest = line.text().substring(1).stripLeading();
            int restIndent = indent + (line.text().length() - rest.length());
            if (rest.isEmpty()) {
                pos[0]++;
                if (pos[0] < lines.size() && lines.get(pos[0]).indent() > indent) {
                    array.add(block(lines, pos, lines.get(pos[0]).indent()));
                } else {
                    array.add(NODES.nullNode());
                }
            } else if (ENTRY.matcher(rest).matches() && !rest.startsWith("{") && !rest.startsWith("[")) {
                // 「- key: value」：这一项是个映射，它的键从 rest 那一列开始，后面同一列的行也是它的
                lines.set(pos[0], new Line(restIndent, rest, line.number()));
                array.add(mapping(lines, pos, restIndent));
            } else {
                array.add(scalar(rest));
                pos[0]++;
            }
        }
        return array;
    }

    private static boolean isItem(String text) {
        return text.equals("-") || text.startsWith("- ");
    }

    private static JsonNode scalar(String value) {
        if (value.startsWith("{") || value.startsWith("[")) {
            try {
                return JSON.readTree(value);
            } catch (IOException e) {
                if (value.startsWith("[") && value.endsWith("]")) {     // [a, b] 这种不带引号的 YAML 行内列表
                    ArrayNode array = NODES.arrayNode();
                    for (String part : value.substring(1, value.length() - 1).split(",")) {
                        if (!part.isBlank()) {
                            array.add(scalar(part.strip()));
                        }
                    }
                    return array;
                }
                return NODES.textNode(value);
            }
        }
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            try {
                return JSON.readTree(value);       // 双引号串按 JSON 的转义读
            } catch (IOException e) {
                return NODES.textNode(value.substring(1, value.length() - 1));
            }
        }
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            return NODES.textNode(value.substring(1, value.length() - 1).replace("''", "'"));
        }
        switch (value) {
            case "true":
                return NODES.booleanNode(true);
            case "false":
                return NODES.booleanNode(false);
            case "null", "~":
                return NODES.nullNode();
            default:
                break;
        }
        if (INTEGER.matcher(value).matches()) {
            long n = Long.parseLong(value);
            return n == (int) n ? NODES.numberNode((int) n) : NODES.numberNode(n);
        }
        return NODES.textNode(value);
    }

    private static String unquoteKey(String key) {
        return key.length() >= 2 && (key.startsWith("\"") || key.startsWith("'")) ? key.substring(1, key.length() - 1) : key;
    }

    private static IllegalArgumentException malformed(Line line) {
        return new IllegalArgumentException("frontmatter 第 " + line.number() + " 行缩进对不上: " + line.text());
    }
}
