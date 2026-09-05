package com.repoinspector.runner.startup;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Parsing and ownership rules for Java agent arguments owned by RepoBuddy. */
public final class RepoBuddyAgentArguments {
    private static final String PREFIX = "-javaagent:";
    private static final Pattern REPO_BUDDY_JAR = Pattern.compile("(?i)^repobuddy-agent(?:-(?:[0-9a-f]{8,}|\\d+(?:\\.\\d+){1,3}))?\\.jar$");
    private RepoBuddyAgentArguments() { }
    public static boolean isRepoBuddyAgentArgument(String argument) {
        if (argument == null || !argument.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) return false;
        String value = argument.substring(PREFIX.length()).trim();
        String path;
        if (value.startsWith("\"")) {
            int closingQuote = value.indexOf('\"', 1);
            path = closingQuote >= 0 ? value.substring(1, closingQuote) : value.substring(1);
        } else {
            int optionStart = value.indexOf('=');
            path = optionStart >= 0 ? value.substring(0, optionStart) : value;
        }
        path = path.replace('\\', '/');
        int slash = path.lastIndexOf('/');
        return REPO_BUDDY_JAR.matcher(slash >= 0 ? path.substring(slash + 1) : path).matches();
    }
    public static String removeRepoBuddyAgentArguments(String vmOptions) {
        if (vmOptions == null || vmOptions.isBlank()) return vmOptions;
        List<String> kept = new ArrayList<>();
        for (String argument : tokenize(vmOptions)) if (!isRepoBuddyAgentArgument(argument)) kept.add(argument);
        return kept.isEmpty() ? null : String.join(" ", kept);
    }
    public static List<String> tokenize(String value) {
        List<String> result = new ArrayList<>(); if (value == null || value.isBlank()) return result;
        StringBuilder token = new StringBuilder(); boolean quoted = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i); if (c == '\"') quoted = !quoted;
            if (Character.isWhitespace(c) && !quoted) { if (token.length() > 0) { result.add(token.toString()); token.setLength(0); } } else token.append(c);
        }
        if (token.length() > 0) result.add(token.toString()); return result;
    }
}
