package dev.trentdb.planner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class PlanTreeRenderer {
    private static final int NODE_WIDTH = 29;
    private static final int CONTENT_WIDTH = NODE_WIDTH - 2;
    private static final int CHILD_GAP = 4;

    private static final char TOP_LEFT = '\u250c';
    private static final char TOP_RIGHT = '\u2510';
    private static final char BOTTOM_LEFT = '\u2514';
    private static final char BOTTOM_RIGHT = '\u2518';
    private static final char HORIZONTAL = '\u2500';
    private static final char VERTICAL = '\u2502';
    private static final char TEE_UP = '\u2534';
    private static final char TEE_DOWN = '\u252c';
    private static final char CROSS = '\u253c';

    public String print(Node node) {
        return String.join("\n", render(node).lines()) + "\n";
    }

    public String title(String title) {
        return print(Node.leaf(title));
    }

    private RenderedBlock render(Node node) {
        List<String> nodeLines = boxLines(node);
        if (node.children().isEmpty()) {
            return new RenderedBlock(nodeLines, NODE_WIDTH, NODE_WIDTH / 2);
        }

        ArrayList<RenderedBlock> childBlocks = new ArrayList<>(node.children().size());
        for (Node child : node.children()) {
            childBlocks.add(render(child));
        }

        int childWidth = childrenWidth(childBlocks);
        int width = Math.max(NODE_WIDTH, childWidth);
        int nodeLeft = (width - NODE_WIDTH) / 2;
        int nodeCenter = nodeLeft + NODE_WIDTH / 2;
        int childrenLeft = (width - childWidth) / 2;
        List<Integer> childCenters = childCenters(childBlocks, childrenLeft);

        ArrayList<String> lines = new ArrayList<>();
        for (String line : nodeLines) {
            lines.add(padToWidth(" ".repeat(nodeLeft) + line, width));
        }
        appendConnectors(lines, width, nodeCenter, childCenters);
        lines.addAll(childLines(childBlocks, childrenLeft, width));
        return new RenderedBlock(lines, width, nodeCenter);
    }

    private void appendConnectors(ArrayList<String> lines, int width, int parentCenter, List<Integer> childCenters) {
        if (childCenters.size() == 1 && childCenters.getFirst() == parentCenter) {
            lines.add(connectorLine(width, List.of(parentCenter), VERTICAL));
            return;
        }
        lines.add(connectorLine(width, List.of(parentCenter), VERTICAL));
        lines.add(horizontalConnector(width, parentCenter, childCenters));
        lines.add(connectorLine(width, childCenters, VERTICAL));
    }

    private List<String> boxLines(Node node) {
        ArrayList<String> lines = new ArrayList<>();
        lines.add(TOP_LEFT + repeat(HORIZONTAL, CONTENT_WIDTH) + TOP_RIGHT);
        lines.add(boxContent(center(node.name(), CONTENT_WIDTH)));
        if (!node.entries().isEmpty()) {
            lines.add(boxContent(center(repeat(HORIZONTAL, 19), CONTENT_WIDTH)));
            for (Entry entry : node.entries()) {
                if (!entry.label().isBlank()) {
                    lines.add(boxContent(center(entry.label() + ":", CONTENT_WIDTH)));
                }
                for (String value : entry.values()) {
                    for (String segment : wrap(value, CONTENT_WIDTH)) {
                        lines.add(boxContent(center(segment, CONTENT_WIDTH)));
                    }
                }
            }
        }
        lines.add(BOTTOM_LEFT + repeat(HORIZONTAL, CONTENT_WIDTH) + BOTTOM_RIGHT);
        return lines;
    }

    private List<Integer> childCenters(List<RenderedBlock> childBlocks, int childrenLeft) {
        ArrayList<Integer> centers = new ArrayList<>(childBlocks.size());
        int cursor = childrenLeft;
        for (RenderedBlock child : childBlocks) {
            centers.add(cursor + child.center());
            cursor += child.width() + CHILD_GAP;
        }
        return List.copyOf(centers);
    }

    private int childrenWidth(List<RenderedBlock> childBlocks) {
        int width = 0;
        for (int index = 0; index < childBlocks.size(); index++) {
            if (index > 0) {
                width += CHILD_GAP;
            }
            width += childBlocks.get(index).width();
        }
        return width;
    }

    private String horizontalConnector(int width, int parentCenter, List<Integer> childCenters) {
        char[] line = spaces(width);
        int first = parentCenter;
        int last = parentCenter;
        for (int childCenter : childCenters) {
            first = Math.min(first, childCenter);
            last = Math.max(last, childCenter);
        }
        for (int index = first; index <= last; index++) {
            line[index] = HORIZONTAL;
        }
        line[parentCenter] = TEE_UP;
        for (int childCenter : childCenters) {
            line[childCenter] = line[childCenter] == TEE_UP ? CROSS : TEE_DOWN;
        }
        return new String(line);
    }

    private String connectorLine(int width, List<Integer> centers, char connector) {
        char[] line = spaces(width);
        for (int center : centers) {
            line[center] = connector;
        }
        return new String(line);
    }

    private List<String> childLines(List<RenderedBlock> childBlocks, int childrenLeft, int width) {
        int height = 0;
        for (RenderedBlock child : childBlocks) {
            height = Math.max(height, child.lines().size());
        }

        ArrayList<String> lines = new ArrayList<>(height);
        for (int lineIndex = 0; lineIndex < height; lineIndex++) {
            StringBuilder builder = new StringBuilder(" ".repeat(childrenLeft));
            for (int childIndex = 0; childIndex < childBlocks.size(); childIndex++) {
                if (childIndex > 0) {
                    builder.append(" ".repeat(CHILD_GAP));
                }
                RenderedBlock child = childBlocks.get(childIndex);
                if (lineIndex < child.lines().size()) {
                    builder.append(child.lines().get(lineIndex));
                } else {
                    builder.append(" ".repeat(child.width()));
                }
            }
            lines.add(padToWidth(builder.toString(), width));
        }
        return List.copyOf(lines);
    }

    private List<String> wrap(String value, int width) {
        ArrayList<String> result = new ArrayList<>();
        String[] paragraphs = value.split("\\R", -1);
        for (String paragraph : paragraphs) {
            wrapParagraph(paragraph, width, result);
        }
        return List.copyOf(result);
    }

    private void wrapParagraph(String paragraph, int width, ArrayList<String> result) {
        if (paragraph.isBlank()) {
            result.add("");
            return;
        }
        String[] words = paragraph.split(" ");
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            appendWrappedWord(word, width, line, result);
        }
        if (line.length() > 0) {
            result.add(line.toString());
        }
    }

    private void appendWrappedWord(String word, int width, StringBuilder line, ArrayList<String> result) {
        if (word.length() > width) {
            if (line.length() > 0) {
                result.add(line.toString());
                line.setLength(0);
            }
            for (int offset = 0; offset < word.length(); offset += width) {
                result.add(word.substring(offset, Math.min(offset + width, word.length())));
            }
            return;
        }
        int nextLength = line.length() == 0 ? word.length() : line.length() + 1 + word.length();
        if (nextLength > width) {
            result.add(line.toString());
            line.setLength(0);
        }
        if (line.length() > 0) {
            line.append(' ');
        }
        line.append(word);
    }

    private String boxContent(String content) {
        return VERTICAL + content + VERTICAL;
    }

    private String center(String value, int width) {
        if (value.length() >= width) {
            return value;
        }
        int left = (width - value.length()) / 2;
        int right = width - value.length() - left;
        return " ".repeat(left) + value + " ".repeat(right);
    }

    private String padToWidth(String value, int width) {
        if (value.length() >= width) {
            return value;
        }
        return value + " ".repeat(width - value.length());
    }

    private String repeat(char value, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, value);
        return new String(chars);
    }

    private char[] spaces(int width) {
        char[] chars = new char[width];
        Arrays.fill(chars, ' ');
        return chars;
    }

    public record Node(String name, List<Entry> entries, List<Node> children) {
        public Node {
            entries = List.copyOf(entries);
            children = List.copyOf(children);
        }

        public static Node leaf(String name) {
            return new Node(name, List.of(), List.of());
        }

        public static Node of(String name, List<Entry> entries, List<Node> children) {
            return new Node(name, entries, children);
        }
    }

    public record Entry(String label, List<String> values) {
        public Entry {
            values = List.copyOf(values);
        }

        public static Entry of(String label, String value) {
            return new Entry(label, List.of(value));
        }

        public static Entry of(String label, List<String> values) {
            return new Entry(label, values);
        }
    }

    private record RenderedBlock(List<String> lines, int width, int center) {
    }
}
