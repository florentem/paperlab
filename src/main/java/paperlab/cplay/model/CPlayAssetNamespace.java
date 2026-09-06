package paperlab.cplay.model;

public enum CPlayAssetNamespace {
    GLOBAL(0, 'g', "global"),
    WORLD(1, 'w', "world");

    private final int index;
    private final char identifier;
    private final String name;

    CPlayAssetNamespace(int index, char identifier, String name) {
        this.index = index;
        this.identifier = identifier;
        this.name = name;
    }

    public int getIndex() { return index; }
    public char getIdentifier() { return identifier; }
    public String getName() { return name; }

    public static CPlayAssetNamespace fromIndex(int index) {
        return (index == 0) ? GLOBAL : (index == 1 ? WORLD : null);
    }

    public static CPlayAssetNamespace fromIdentifier(char c) {
        final char lower = Character.toLowerCase(c);
        if (lower == 'w') return WORLD;
        if (lower == 'g') return GLOBAL;
        return null;
    }
}
