package paperlab.zone;

import org.bukkit.Color;

/**
 * An axis-aligned 3D cuboid representing a box within a tick zone.
 */
public record ZoneBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public ZoneBox {
        final int x1 = Math.min(minX, maxX);
        final int x2 = Math.max(minX, maxX);
        final int y1 = Math.min(minY, maxY);
        final int y2 = Math.max(minY, maxY);
        final int z1 = Math.min(minZ, maxZ);
        final int z2 = Math.max(minZ, maxZ);
        minX = x1;
        maxX = x2;
        minY = y1;
        maxY = y2;
        minZ = z1;
        maxZ = z2;
    }

    public static ZoneBox of(final int x1, final int y1, final int z1,
                             final int x2, final int y2, final int z2) {
        return new ZoneBox(x1, y1, z1, x2, y2, z2);
    }

    public int sizeX() {
        return maxX - minX + 1;
    }

    public int sizeY() {
        return maxY - minY + 1;
    }

    public int sizeZ() {
        return maxZ - minZ + 1;
    }

    public long volume() {
        return (long) sizeX() * sizeY() * sizeZ();
    }

    public boolean contains(final int x, final int y, final int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    /**
     * Golden ratio HSV hue calculation for high visual contrast between successive indices.
     */
    public static Color getDistinctColor(final int index) {
        final float goldenRatioConjugate = 0.618033988749895f;
        float hue = (index * goldenRatioConjugate) % 1.0f;
        if (hue < 0) {
            hue += 1.0f;
        }
        final float saturation = 0.85f;
        final float value = 0.95f;
        final int rgb = java.awt.Color.HSBtoRGB(hue, saturation, value);
        final int r = (rgb >> 16) & 0xFF;
        final int g = (rgb >> 8) & 0xFF;
        final int b = rgb & 0xFF;
        return Color.fromRGB(r, g, b);
    }

    public static String getHexColor(final int index) {
        final Color c = getDistinctColor(index);
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }
}
