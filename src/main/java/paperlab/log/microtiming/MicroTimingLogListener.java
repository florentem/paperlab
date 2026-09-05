package paperlab.log.microtiming;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import paperlab.core.CoreBridge;
import paperlab.counter.WoolColors;

/**
 * Слушатель и мост для /log microtiming.
 */
public final class MicroTimingLogListener implements Listener {

    public static void init() {
        if (CoreBridge.PRESENT) {
            CoreDelegate.register();
        }
    }

    private static final class CoreDelegate {
        static void register() {
            io.papermc.paper.lab.microtiming.LabMicroTiming.addListener(new io.papermc.paper.lab.microtiming.LabMicroTiming.Listener() {
                @Override
                public void onBlockStateChange(final net.minecraft.world.level.Level level, final net.minecraft.core.BlockPos pos,
                                               final net.minecraft.world.level.block.state.BlockState oldState,
                                               final net.minecraft.world.level.block.state.BlockState newState,
                                               final net.minecraft.world.item.DyeColor color) {
                    if (!MicroTimingLogger.hasSubscribers()) return;
                    final String dim = level.dimension().identifier().toString();
                    final String name = newState.getBlock().getName().getString();
                    final String diff = describeChange(oldState, newState);
                    final int depth = io.papermc.paper.lab.microtiming.LabMicroTiming.currentDepth();
                    final String phase = io.papermc.paper.lab.microtiming.LabMicroTiming.currentPhase().label();
                    final String stack = captureStackTrace();
                    MicroTimingLogger.recordEvent(level.getGameTime(), dim, pos.getX(), pos.getY(), pos.getZ(),
                        color, name, "state change", diff, depth, phase, stack);
                }

                @Override
                public void onBlockEvent(final net.minecraft.world.level.Level level, final net.minecraft.core.BlockPos pos,
                                         final net.minecraft.world.level.block.Block block, final int type, final int data,
                                         final net.minecraft.world.item.DyeColor color) {
                    if (!MicroTimingLogger.hasSubscribers()) return;
                    final String dim = level.dimension().identifier().toString();
                    final String name = block.getName().getString();
                    final String action = type == 0 ? "push" : (type == 1 || type == 2 ? "retract" : "block event");
                    final int depth = io.papermc.paper.lab.microtiming.LabMicroTiming.currentDepth();
                    final String phase = io.papermc.paper.lab.microtiming.LabMicroTiming.currentPhase().label();
                    final String stack = captureStackTrace();
                    MicroTimingLogger.recordEvent(level.getGameTime(), dim, pos.getX(), pos.getY(), pos.getZ(),
                        color, name, action, "type=" + type + " data=" + data, depth, phase, stack);
                }

                @Override
                public void onTileTick(final net.minecraft.world.level.Level level, final net.minecraft.core.BlockPos pos,
                                       final net.minecraft.world.level.block.Block block,
                                       final net.minecraft.world.item.DyeColor color) {
                    if (!MicroTimingLogger.hasSubscribers()) return;
                    final String dim = level.dimension().identifier().toString();
                    final String name = block.getName().getString();
                    final int depth = io.papermc.paper.lab.microtiming.LabMicroTiming.currentDepth();
                    final String phase = io.papermc.paper.lab.microtiming.LabMicroTiming.currentPhase().label();
                    final String stack = captureStackTrace();
                    MicroTimingLogger.recordEvent(level.getGameTime(), dim, pos.getX(), pos.getY(), pos.getZ(),
                        color, name, "scheduled tick", "executed", depth, phase, stack);
                }
            });
        }

        private static String captureStackTrace() {
            final StackTraceElement[] st = Thread.currentThread().getStackTrace();
            final StringBuilder sb = new StringBuilder();
            int count = 0;
            for (int i = 3; i < st.length && count < 8; i++) {
                final String cls = st[i].getClassName();
                if (cls.contains("paperlab") || cls.contains("LabMicroTiming") || cls.startsWith("java.lang")) {
                    continue;
                }
                if (!sb.isEmpty()) sb.append("\n");
                final String simpleName = cls.substring(cls.lastIndexOf('.') + 1);
                sb.append(simpleName).append(".").append(st[i].getMethodName())
                  .append(":").append(st[i].getLineNumber());
                count++;
            }
            return sb.toString();
        }

        private static String describeChange(final net.minecraft.world.level.block.state.BlockState oldState,
                                             final net.minecraft.world.level.block.state.BlockState newState) {
            final StringBuilder sb = new StringBuilder();
            for (final var prop : newState.getProperties()) {
                final Object oldVal = oldState.hasProperty(prop) ? oldState.getValue(prop) : null;
                final Object newVal = newState.getValue(prop);
                if (oldVal != null && !oldVal.equals(newVal)) {
                    if (!sb.isEmpty()) sb.append(", ");
                    sb.append(prop.getName()).append(": ").append(oldVal).append(" -> ").append(newVal);
                }
            }
            return sb.isEmpty() ? "updated" : sb.toString();
        }
    }

    /** Установка маркеров красителями: циклическое переключение (REGULAR -> END_ROD -> REMOVE) */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerInteract(final PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
        if (event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        final ItemStack item = event.getItem();
        if (item == null || !item.getType().name().endsWith("_DYE")) return;
        final Player player = event.getPlayer();
        if (!player.hasPermission("paperlab.log.microtiming")) return;

        final String typeName = item.getType().name();
        final String colorPrefix = typeName.substring(0, typeName.length() - 4);
        final net.minecraft.world.item.DyeColor dye = net.minecraft.world.item.DyeColor.byName(colorPrefix.toLowerCase(), null);
        if (dye != null && CoreBridge.PRESENT) {
            final Block b = event.getClickedBlock();
            final net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(b.getX(), b.getY(), b.getZ());
            final var cycle = io.papermc.paper.lab.microtiming.LabMicroTiming.cycleMarker(pos, dye);
            final String coords = b.getX() + " " + b.getY() + " " + b.getZ();
            final net.kyori.adventure.text.Component feedback = switch (cycle.resultType()) {
                case ADDED -> net.kyori.adventure.text.Component.text("Added MicroTiming marker: ", net.kyori.adventure.text.format.NamedTextColor.GREEN)
                    .append(net.kyori.adventure.text.Component.text(dye.getName() + " (REGULAR)", net.kyori.adventure.text.format.NamedTextColor.YELLOW))
                    .append(net.kyori.adventure.text.Component.text(" at " + coords, net.kyori.adventure.text.format.NamedTextColor.GRAY));
                case SWITCHED -> net.kyori.adventure.text.Component.text("Switched MicroTiming marker: ", net.kyori.adventure.text.format.NamedTextColor.AQUA)
                    .append(net.kyori.adventure.text.Component.text(dye.getName() + " (END_ROD)", net.kyori.adventure.text.format.NamedTextColor.GOLD))
                    .append(net.kyori.adventure.text.Component.text(" at " + coords, net.kyori.adventure.text.format.NamedTextColor.GRAY));
                case REMOVED -> net.kyori.adventure.text.Component.text("Removed MicroTiming marker at " + coords, net.kyori.adventure.text.format.NamedTextColor.RED);
                case COLOR_CHANGED -> net.kyori.adventure.text.Component.text("Changed MicroTiming marker color to ", net.kyori.adventure.text.format.NamedTextColor.GREEN)
                    .append(net.kyori.adventure.text.Component.text(dye.getName() + " (REGULAR)", net.kyori.adventure.text.format.NamedTextColor.YELLOW))
                    .append(net.kyori.adventure.text.Component.text(" at " + coords, net.kyori.adventure.text.format.NamedTextColor.GRAY));
            };
            player.sendMessage(feedback);
            event.setCancelled(true);
        }
    }

    // Запасные слушатели для чистого Paper
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRedstone(final BlockRedstoneEvent event) {
        if (CoreBridge.PRESENT || !MicroTimingLogger.hasSubscribers()) return;
        final Block b = event.getBlock();
        final Block below = b.getRelative(BlockFace.DOWN);
        final net.minecraft.world.item.DyeColor color = WoolColors.byMaterial(below.getType());
        if (color == null) return;

        final Location loc = b.getLocation();
        final long time = b.getWorld().getGameTime();
        final String name = b.getType().name().toLowerCase();
        final String details = "power: " + event.getOldCurrent() + " -> " + event.getNewCurrent();
        MicroTimingLogger.recordEvent(time, b.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
            color, name, "redstone change", details, 0);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(final BlockPistonExtendEvent event) {
        if (CoreBridge.PRESENT || !MicroTimingLogger.hasSubscribers()) return;
        handlePiston(event.getBlock(), "push");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(final BlockPistonRetractEvent event) {
        if (CoreBridge.PRESENT || !MicroTimingLogger.hasSubscribers()) return;
        handlePiston(event.getBlock(), "retract");
    }

    private void handlePiston(final Block b, final String action) {
        final Block below = b.getRelative(BlockFace.DOWN);
        final net.minecraft.world.item.DyeColor color = WoolColors.byMaterial(below.getType());
        if (color == null) return;

        final Location loc = b.getLocation();
        final long time = b.getWorld().getGameTime();
        final String name = b.getType().name().toLowerCase();
        MicroTimingLogger.recordEvent(time, b.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
            color, name, action, "direction=" + b.getBlockData().getAsString(), 1);
    }
}
