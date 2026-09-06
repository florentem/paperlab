package paperlab.text;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * A port of Carpet's markup mini-language ({@code carpet.utils.Messenger}) to Adventure.
 *
 * <p>It exists for one reason: so our messages look <b>exactly</b> like Carpet's. While every
 * message was coloured by hand they came out similar but not the same — a different shade, a
 * different indent, buttons in the wrong place. With the same primitive, lines can be carried
 * over from Carpet's sources verbatim, and divergence becomes impossible rather than unlikely.
 *
 * <h2>The language</h2>
 * Each field is a string of the form {@code "<style> <text>"}: style up to the first space,
 * text after it. A style is a set of letters; their order inside the field does not matter,
 * and the last colour in check order wins:
 *
 * <pre>
 * i italic    s strikethrough  u underline        b bold          o obfuscated
 * w white     y yellow         m light purple     r red           c aqua
 * l green     t blue           f dark gray        g gray          d gold
 * p dark purple  n dark red    q dark aqua        e dark green    v dark blue
 * k black     #rrggbb arbitrary colour
 * </pre>
 *
 * <p>A field beginning with a control character adds no text; it attaches an event to the
 * <b>previous</b> field:
 *
 * <pre>
 * ^ hover tooltip              ! run command      ? suggest command
 * &#64; open URL                    &amp; copy to clipboard
 * </pre>
 *
 * <p>A string starting with a space counts as white: {@code " - foo"} is the same as
 * {@code "w  - foo"}.
 *
 * <p>The difference from Carpet is purely technical: there components are mutable and an event
 * is attached by editing the style in place, whereas Adventure's are immutable — so we keep
 * the last piece separately and replace it whole.
 *
 * <p>Based on {@code carpet.utils.Messenger} from Carpet Mod (MIT, (c) gnembon). Terms and the
 * full list of borrowings are in THIRD-PARTY.md.
 */
public final class Msg {

    private Msg() {
    }

    /** Assemble one line from the fields. */
    public static Component c(final Object... fields) {
        final List<Component> parts = new ArrayList<>();
        for (final Object field : fields) {
            if (field instanceof final Component ready) {
                parts.add(ready);
                continue;
            }
            final String text = String.valueOf(field);
            if (text.isEmpty()) {
                parts.add(Component.empty());
                continue;
            }
            String message = text;
            if (Character.isWhitespace(message.charAt(0))) {
                message = "w" + message;
            }
            // A control field edits the previous piece; an ordinary one appends a new piece.
            //
            // Carpet tells them apart by reference identity: its components are mutable and a
            // modifier returns the same object. Adventure's are immutable — the reference is
            // always new, and such a check silently duplicates every piece that carries an
            // event. So we look at the character instead.
            if (!parts.isEmpty() && MODIFIERS.indexOf(message.charAt(0)) >= 0) {
                parts.set(parts.size() - 1, modify(parts.get(parts.size() - 1), message));
            } else {
                parts.add(literal(message));
            }
        }
        Component out = Component.empty();
        for (final Component part : parts) {
            out = out.append(part);
        }
        return out;
    }

    /** Assemble and send. */
    public static void m(final Audience target, final Object... fields) {
        if (target != null) {
            target.sendMessage(c(fields));
        }
    }

    /** Plain text with no markup — like {@code Messenger.s}. */
    public static Component s(final String text) {
        return Component.text(text, NamedTextColor.WHITE);
    }

    // ------------------------------------------------------------------- parsing

    /** Characters that attach an event to the previous piece instead of adding text. */
    private static final String MODIFIERS = "?!^@&";

    private static Component literal(final String message) {
        final int limit = message.indexOf(' ');
        final String desc = limit >= 0 ? message.substring(0, limit) : message;
        final String body = limit >= 0 ? message.substring(limit + 1) : "";
        return Component.text(body).style(parseStyle(desc));
    }

    private static Component modify(final Component previous, final String message) {
        final String payload = message.substring(1);
        return switch (message.charAt(0)) {
            case '?' -> previous.clickEvent(ClickEvent.suggestCommand(payload));
            case '!' -> previous.clickEvent(ClickEvent.runCommand(payload));
            case '^' -> previous.hoverEvent(HoverEvent.showText(c(payload)));
            case '@' -> previous.clickEvent(ClickEvent.openUrl(URI.create(payload).toString()));
            case '&' -> previous.clickEvent(ClickEvent.copyToClipboard(payload));
            default -> previous;
        };
    }

    /**
     * Parse a set of style letters.
     *
     * <p>As in Carpet: the string is scanned for every code in turn, so the letter order inside
     * the field decides nothing — the check order here does. The default colour is white.
     */
    static Style parseStyle(final String desc) {
        Style.Builder style = Style.style().color(NamedTextColor.WHITE);
        if (desc.indexOf('i') >= 0) {
            style = style.decorate(TextDecoration.ITALIC);
        }
        if (desc.indexOf('s') >= 0) {
            style = style.decorate(TextDecoration.STRIKETHROUGH);
        }
        if (desc.indexOf('u') >= 0) {
            style = style.decorate(TextDecoration.UNDERLINED);
        }
        if (desc.indexOf('b') >= 0) {
            style = style.decorate(TextDecoration.BOLD);
        }
        if (desc.indexOf('o') >= 0) {
            style = style.decorate(TextDecoration.OBFUSCATED);
        }
        for (final Code code : COLOURS) {
            if (desc.indexOf(code.letter()) >= 0) {
                style = style.color(code.colour());
            }
        }
        final int hash = desc.indexOf('#');
        if (hash >= 0 && desc.length() >= hash + 7) {
            final TextColor custom = TextColor.fromHexString(desc.substring(hash, hash + 7));
            if (custom != null) {
                style = style.color(custom);
            }
        }
        return style.build();
    }

    private record Code(char letter, NamedTextColor colour) {
    }

    /** Same order as Carpet's enum: the last match wins. */
    private static final Code[] COLOURS = {
        new Code('w', NamedTextColor.WHITE),
        new Code('y', NamedTextColor.YELLOW),
        new Code('m', NamedTextColor.LIGHT_PURPLE),
        new Code('r', NamedTextColor.RED),
        new Code('c', NamedTextColor.AQUA),
        new Code('l', NamedTextColor.GREEN),
        new Code('t', NamedTextColor.BLUE),
        new Code('f', NamedTextColor.DARK_GRAY),
        new Code('g', NamedTextColor.GRAY),
        new Code('d', NamedTextColor.GOLD),
        new Code('p', NamedTextColor.DARK_PURPLE),
        new Code('n', NamedTextColor.DARK_RED),
        new Code('q', NamedTextColor.DARK_AQUA),
        new Code('e', NamedTextColor.DARK_GREEN),
        new Code('v', NamedTextColor.DARK_BLUE),
        new Code('k', NamedTextColor.BLACK),
    };

    /**
     * Carpet's heat scale: how close {@code actual} is to {@code reference}.
     *
     * <p>Needed where a number alone says nothing: mobcaps, counters. "Nearly capped" and
     * "capped" should read as colour rather than as a comparison done by eye.
     */
    public static String heatmap(final double actual, final double reference) {
        String colour = "g";
        if (actual >= 0.0D) {
            colour = "e";
        }
        if (actual > 0.5D * reference) {
            colour = "y";
        }
        if (actual > 0.8D * reference) {
            colour = "r";
        }
        if (actual > reference) {
            colour = "m";
        }
        return colour;
    }

    /** Mob category colour — the same as in Carpet. */
    public static String creatureTypeColour(final String category) {
        return switch (category.toLowerCase(Locale.ROOT)) {
            case "monster" -> "n";
            case "creature" -> "e";
            case "ambient" -> "f";
            case "water_creature" -> "v";
            case "water_ambient" -> "q";
            default -> "w";
        };
    }

    /** A teleport link to coordinates — like {@code Messenger.tp}. */
    public static Component tp(final String style, final int x, final int y, final int z) {
        return Component.text(String.format("[ %d, %d, %d ]", x, y, z))
            .style(parseStyle(style))
            .clickEvent(ClickEvent.suggestCommand(String.format("/tp %d %d %d", x, y, z)))
            .hoverEvent(HoverEvent.showText(Component.text("Click to teleport")));
    }
}
