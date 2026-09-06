package paperlab.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

/**
 * Checks for the port of Carpet's markup.
 *
 * <p>The important one is {@link #modifierDoesNotDuplicateText()}. The first version of
 * {@link Msg} followed Carpet literally: there a control field edits the component in place, and
 * reference identity was the signal. Adventure's components are immutable, the reference is
 * always new, and every piece carrying a button or a tooltip was silently duplicated. In the log
 * it looked like
 * {@code - fakePlayerNameSuffix - fakePlayerNameSuffix - fakePlayerNameSuffix}.
 */
class MsgTest {

    private static String plain(final Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    @Test
    void plainFieldKeepsTextAfterStyle() {
        assertEquals("hello", plain(Msg.c("w hello")));
    }

    @Test
    void leadingSpaceMeansWhite() {
        // " - foo" becomes "w - foo", and the first space is consumed as the separator between
        // style and text. That is why Carpet writes an indent with two spaces: "w  - foo".
        final Component one = Msg.c(" - foo");
        assertEquals("- foo", plain(one));
        assertEquals(NamedTextColor.WHITE, one.children().get(0).color());
        assertEquals(" - foo", plain(Msg.c("w  - foo")));
    }

    @Test
    void modifierDoesNotDuplicateText() {
        final Component out = Msg.c("w rule", "!/carpet rule", "^g hint");
        assertEquals("rule", plain(out));
    }

    @Test
    void modifierAttachesEventToPreviousPart() {
        final Component out = Msg.c("w rule", "!/carpet rule");
        final Component part = out.children().get(0);
        final ClickEvent click = part.clickEvent();
        assertNotNull(click);
        assertEquals(ClickEvent.Action.RUN_COMMAND, click.action());
        assertTrue(click.toString().contains("/carpet rule"), click.toString());
    }

    @Test
    void severalFieldsConcatenateInOrder() {
        assertEquals("a b", plain(Msg.c("w a", "g  b")));
    }

    @Test
    void lastColourCodeWins() {
        // "lb" is green and bold: one colour letter, and b is a decoration.
        final Component out = Msg.c("lb x");
        final Component part = out.children().get(0);
        assertEquals(NamedTextColor.GREEN, part.color());
        assertEquals(TextDecoration.State.TRUE, part.decoration(TextDecoration.BOLD));
    }

    @Test
    void hexColourIsParsed() {
        final Component part = Msg.c("#ff8800 x").children().get(0);
        assertNotNull(part.color());
        assertEquals(0xff8800, part.color().value());
    }

    @Test
    void emptyFieldProducesEmptyLine() {
        assertEquals("", plain(Msg.c("")));
    }

    @Test
    void modifierOnFirstFieldIsTreatedAsText() {
        // There is nothing to attach an event to — Carpet creates a plain component here.
        assertTrue(plain(Msg.c("^g hint")).contains("hint"));
    }

    @Test
    void heatmapMatchesCarpetThresholds() {
        assertEquals("e", Msg.heatmap(0.0D, 50.0D));
        assertEquals("y", Msg.heatmap(30.0D, 50.0D));
        assertEquals("r", Msg.heatmap(45.0D, 50.0D));
        assertEquals("m", Msg.heatmap(60.0D, 50.0D));
    }
}
