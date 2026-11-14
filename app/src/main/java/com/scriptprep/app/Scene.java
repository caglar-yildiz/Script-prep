package com.scriptprep.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Scene {
    private static final Locale TURKISH = Locale.forLanguageTag("tr-TR");
    private static final String ROLE_PATTERN =
        "[\\p{L}][\\p{L}\\p{M}\\p{N} .’'-]{0,39}";
    private static final Pattern INLINE_ROLE =
        Pattern.compile("^(" + ROLE_PATTERN + ")\\s*:\\s*(.*)$");
    private static final Pattern ROLE_ONLY = Pattern.compile(ROLE_PATTERN);

    final List<Line> lines;
    final List<String> roles;

    private Scene(List<Line> lines, List<String> roles) {
        this.lines = Collections.unmodifiableList(lines);
        this.roles = Collections.unmodifiableList(roles);
    }

    static Scene parse(String source) {
        List<Line> lines = new ArrayList<>();
        Map<String, String> roles = new LinkedHashMap<>();
        String role = null;
        StringBuilder dialogue = new StringBuilder();

        String[] rows = source.replace('\u00A0', ' ').split("\r\n|[\r\n]");
        boolean hasInlineRoles = false;
        for (String row : rows) {
            if (INLINE_ROLE.matcher(row.trim()).matches()) {
                hasInlineRoles = true;
                break;
            }
        }

        for (String rawLine : rows) {
            String row = rawLine.trim();
            if (row.isEmpty() || (row.startsWith("(") && row.endsWith(")"))) {
                continue;
            }

            Matcher inline = INLINE_ROLE.matcher(row);
            boolean standaloneRole = !hasInlineRoles
                && ROLE_ONLY.matcher(row).matches()
                && row.equals(row.toUpperCase(TURKISH))
                && !row.equals(row.toLowerCase(TURKISH))
                && (role == null || dialogue.length() > 0)
                && !row.endsWith(".");

            if (inline.matches() || standaloneRole) {
                addLine(lines, role, dialogue);
                String candidate = (standaloneRole ? row : inline.group(1)).trim();
                String key = candidate.toLowerCase(TURKISH);
                if (!roles.containsKey(key)) {
                    roles.put(key, candidate);
                }
                role = roles.get(key);
                dialogue.setLength(0);
                if (!standaloneRole) {
                    dialogue.append(inline.group(2).trim());
                }
            } else if (role != null) {
                if (dialogue.length() > 0) {
                    dialogue.append(' ');
                }
                dialogue.append(row);
            }
        }

        addLine(lines, role, dialogue);
        List<String> usedRoles = new ArrayList<>();
        for (Line line : lines) {
            if (!usedRoles.contains(line.role)) {
                usedRoles.add(line.role);
            }
        }
        return new Scene(lines, usedRoles);
    }

    private static void addLine(List<Line> lines, String role, StringBuilder dialogue) {
        if (role != null && dialogue.length() > 0) {
            lines.add(new Line(role, dialogue.toString()));
        }
    }

    static final class Line {
        final String role;
        final String text;

        private Line(String role, String text) {
            this.role = role;
            this.text = text;
        }
    }
}
