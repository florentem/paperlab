package paperlab.log;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A HUD logger. Carpet's model: a player subscribes and the value sits permanently in the tab
 * list.
 *
 * <p>Difference from Carpet: one player may hold <b>several</b> subscriptions to one logger, one
 * per target. That is what lets the HUD show the mobcaps of several players and bots at once,
 * and later several counters the same way.
 *
 * <p>An option is stored as a string of the form {@code "[target] [flags]"}. The "target" is the
 * first token that is not a flag; subscriptions are deduplicated by it, so re-subscribing to the
 * same target with a different flag replaces the old one rather than adding a second line.
 */
public final class LabLogger {

    /** Modifier tokens: not treated as a target when deduplicating. */
    private static final Set<String> FLAGS = Set.of("full");

    private final String name;
    private final List<String> options;
    private final String defaultOption;
    /** The option is free text (a player name) and is not validated against a list. */
    private final boolean freeform;

    /** subscriber name -> an immutable set of their options. */
    private final Map<String, Set<String>> subscribers = new ConcurrentHashMap<>();

    LabLogger(final String name, final boolean freeform, final String defaultOption, final List<String> options) {
        this.name = name;
        this.freeform = freeform;
        this.defaultOption = defaultOption;
        this.options = List.copyOf(options);
    }

    LabLogger(final String name, final boolean freeform, final String... options) {
        this.name = name;
        this.freeform = freeform;
        this.defaultOption = "";
        this.options = List.of(options);
    }

    public String name() {
        return this.name;
    }

    public List<String> options() {
        return this.options;
    }

    public String defaultOption() {
        return this.defaultOption;
    }

    public boolean freeform() {
        return this.freeform;
    }

    public boolean hasSubscribers() {
        return !this.subscribers.isEmpty();
    }

    public Map<String, Set<String>> subscribers() {
        return java.util.Collections.unmodifiableMap(this.subscribers);
    }

    /** All of a player's options. An empty collection means not subscribed. */
    public Collection<String> optionsFor(final String playerName) {
        final Set<String> set = this.subscribers.get(playerName);
        return set == null ? List.of() : set;
    }

    public boolean subscribed(final String playerName) {
        return this.subscribers.containsKey(playerName);
    }

    /**
     * Toggles the subscription for one target.
     *
     * <ul>
     *   <li>target not subscribed yet -&gt; add;</li>
     *   <li>subscribed with the same string -&gt; remove;</li>
     *   <li>subscribed with different flags -&gt; replace (turning {@code full} on, say).</li>
     * </ul>
     *
     * @return {@code true} if a subscription for this target exists after the call
     */
    public synchronized boolean toggle(final String playerName, final @Nullable String option) {
        String normalized = option == null ? "" : option.trim();
        if (normalized.isEmpty() && !this.defaultOption.isEmpty()) {
            normalized = this.defaultOption;
        }
        final String target = targetOf(normalized);

        final Set<String> current = this.subscribers.get(playerName);
        final LinkedHashSet<String> newSet = current != null ? new LinkedHashSet<>(current) : new LinkedHashSet<>();

        String existing = null;
        for (final String s : newSet) {
            if (targetOf(s).equals(target)) {
                existing = s;
                break;
            }
        }

        if (existing != null) {
            newSet.remove(existing);
            if (existing.equals(normalized)) {
                // The very same subscription means turning it off.
                if (newSet.isEmpty()) {
                    this.subscribers.remove(playerName);
                } else {
                    this.subscribers.put(playerName, Set.copyOf(newSet));
                }
                return false;
            }
        }

        newSet.add(normalized);
        this.subscribers.put(playerName, Set.copyOf(newSet));
        return true;
    }

    /** Drop all of a player's subscriptions to this logger. */
    public synchronized boolean unsubscribeAll(final String playerName) {
        return this.subscribers.remove(playerName) != null;
    }

    /** Subscription target: the first token that is not a flag. Empty string means the subscriber. */
    static String targetOf(final String option) {
        for (final String token : option.split(" ")) {
            if (!token.isEmpty() && !FLAGS.contains(token.toLowerCase(Locale.ROOT))) {
                return token;
            }
        }
        return "";
    }

    static boolean hasFlag(final String option, final String flag) {
        for (final String token : option.split(" ")) {
            if (token.equalsIgnoreCase(flag)) {
                return true;
            }
        }
        return false;
    }
}
