package com.habitrain.core.config;

import com.google.gson.JsonElement;
import java.util.ArrayDeque;

/** Structural guard before parsing, copying or serializing untrusted configuration. */
public final class JsonInputLimits {
    // Current schemas are below 16 levels; keep room for extension-owned settings.
    public static final int MAX_DEPTH = 64;
    public static final int MAX_NODES = 100_000;
    private JsonInputLimits() {}

    public static String check(String json) {
        if (json == null) throw new IllegalArgumentException("JSON cannot be null");
        int depth = 0;
        boolean quoted = false, escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == '{' || c == '[') {
                if (++depth > MAX_DEPTH) throw new IllegalArgumentException("Configuration nesting exceeds " + MAX_DEPTH);
            } else if (c == '}' || c == ']') depth--;
        }
        return json;
    }

    public static void check(JsonElement root) {
        record Node(JsonElement value, int depth) {}
        var pending = new ArrayDeque<Node>();
        pending.push(new Node(root, 0));
        int nodes = 0;
        while (!pending.isEmpty()) {
            Node node = pending.pop();
            if (++nodes > MAX_NODES || node.depth() > MAX_DEPTH)
                throw new IllegalArgumentException("Configuration structure exceeds budget");
            JsonElement value = node.value();
            if (value == null || value.isJsonNull() || value.isJsonPrimitive()) continue;
            Iterable<JsonElement> children = value.isJsonArray() ? value.getAsJsonArray() : value.getAsJsonObject().asMap().values();
            for (JsonElement child : children) {
                if (pending.size() >= MAX_NODES) throw new IllegalArgumentException("Configuration structure exceeds budget");
                pending.push(new Node(child, node.depth() + 1));
            }
        }
    }
}
