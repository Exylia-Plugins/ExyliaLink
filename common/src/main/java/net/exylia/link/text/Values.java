package net.exylia.link.text;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/** The {@code %name%} placeholders of one message. */
public final class Values {

    private final Map<String, String> values = new LinkedHashMap<>();

    private Values() {
    }

    public static @NotNull Values of() {
        return new Values();
    }

    /** A value that may carry formatting of its own (a configured state such as {@code {success}Connected}). */
    public static @NotNull Values of(@NotNull String name, @Nullable Object value) {
        return new Values().put(name, value);
    }

    public @NotNull Values put(@NotNull String name, @Nullable Object value) {
        values.put(name, value == null ? "" : String.valueOf(value));
        return this;
    }

    /** Text from outside the plugin (a name, an error, a network's own message): shown as it is. */
    public @NotNull Values putText(@NotNull String name, @Nullable String value) {
        values.put(name, Text.escape(value));
        return this;
    }

    public @NotNull String apply(@NotNull String line) {
        if (line.indexOf('%') < 0) {
            return line;
        }
        String out = line;
        for (Map.Entry<String, String> value : values.entrySet()) {
            out = out.replace("%" + value.getKey() + "%", value.getValue());
        }
        return out;
    }
}
