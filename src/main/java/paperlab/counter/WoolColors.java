package paperlab.counter;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.world.item.DyeColor;
import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

/**
 * A wool colour — the counter's trigger.
 *
 * <p>In the core version the table was built from {@code Blocks.WOOL.pick(DyeColor)}: in 26.2
 * there are no separate {@code Blocks.WHITE_WOOL} fields any more. In a plugin it is easier to
 * work with {@link Material}, so the mapping is built by name — {@code RED} to {@code RED_WOOL}.
 * That way nothing breaks if new colours are added either.
 */
public final class WoolColors {

    private static final Map<Material, DyeColor> BY_MATERIAL = new EnumMap<>(Material.class);

    static {
        for (final DyeColor colour : DyeColor.values()) {
            final Material material = Material.matchMaterial(
                colour.getName().toUpperCase(Locale.ROOT) + "_WOOL");
            if (material != null) {
                BY_MATERIAL.put(material, colour);
            }
        }
    }

    private WoolColors() {
    }

    public static @Nullable DyeColor byMaterial(final Material material) {
        return BY_MATERIAL.get(material);
    }

    public static @Nullable DyeColor byName(final String name) {
        return DyeColor.byName(name.toLowerCase(Locale.ROOT), null);
    }
}
