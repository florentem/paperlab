package paperlab.counter;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import net.minecraft.world.item.DyeColor;
import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

/**
 * Цвет шерсти — триггер счётчика.
 *
 * <p>В версии для ядра таблица строилась из {@code Blocks.WOOL.pick(DyeColor)}: в 26.2
 * отдельных полей {@code Blocks.WHITE_WOOL} больше нет. В плагине удобнее работать с
 * {@link Material}, поэтому соответствие строится по имени — {@code RED} → {@code RED_WOOL}.
 * Так при добавлении новых цветов ломаться тоже нечему.
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
