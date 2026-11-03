package net.exylia.link.store;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Reward grants already run here, {@code grants.log}: one {@code <executedAtMillis> <grantId>}
 * line per grant, appended and flushed to disk (fsync) before the grant is acknowledged, so a
 * crash never forgets a grant whose ack went out.
 *
 * <p>Opening compacts the file: lines past the 90-day retention, duplicates and a torn last
 * line (a crash mid-write) are dropped, and the rest is rewritten to a temporary file that
 * replaces the journal atomically. Lookups are from memory.
 */
public final class GrantJournal implements AutoCloseable {

    public static final long KEEP_MILLIS = 90L * 24 * 60 * 60 * 1000;

    private final Path file;
    private final LongSupplier clock;
    private final Map<String, Long> executed = new HashMap<>();
    private FileChannel channel;

    public GrantJournal(@NotNull Path file) throws IOException {
        this(file, System::currentTimeMillis);
    }

    GrantJournal(@NotNull Path file, @NotNull LongSupplier clock) throws IOException {
        this.file = file;
        this.clock = clock;
        compact();
    }

    /** Whether this grant ran here within the retention. */
    public synchronized boolean contains(@NotNull String grantId) {
        Long at = executed.get(grantId);
        return at != null && at >= clock.getAsLong() - KEEP_MILLIS;
    }

    /** Records a grant as run; returns once the line is on disk. */
    public synchronized void record(@NotNull String grantId) throws IOException {
        if (grantId.isBlank() || grantId.indexOf('\n') >= 0 || grantId.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("grant id cannot be journaled: " + grantId);
        }
        long now = clock.getAsLong();
        channel.write(ByteBuffer.wrap((now + " " + grantId + "\n").getBytes(StandardCharsets.UTF_8)));
        channel.force(false);
        executed.put(grantId, now);
    }

    public synchronized int size() {
        return executed.size();
    }

    /** Drops what is past the retention and rewrites the file; safe at any time. */
    public synchronized void compact() throws IOException {
        if (channel != null) {
            channel.close();
        }
        executed.clear();
        long cutoff = clock.getAsLong() - KEEP_MILLIS;
        if (Files.exists(file)) {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            // Without a final newline the last line may be cut short: it was never acknowledged.
            int complete = text.lastIndexOf('\n') + 1;
            for (String line : text.substring(0, complete).split("\n")) {
                int space = line.indexOf(' ');
                if (space <= 0 || space == line.length() - 1) {
                    continue;
                }
                try {
                    long at = Long.parseLong(line.substring(0, space));
                    if (at >= cutoff) {
                        executed.merge(line.substring(space + 1), at, Math::max);
                    }
                } catch (NumberFormatException damaged) {
                    // Skipped: one bad line never loses the others.
                }
            }
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (FileChannel out = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            StringBuilder lines = new StringBuilder();
            executed.forEach((id, at) -> lines.append(at).append(' ').append(id).append('\n'));
            out.write(ByteBuffer.wrap(lines.toString().getBytes(StandardCharsets.UTF_8)));
            out.force(true);
        }
        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        channel = FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
    }

    @Override
    public synchronized void close() throws IOException {
        if (channel != null) {
            channel.close();
            channel = null;
        }
    }
}
