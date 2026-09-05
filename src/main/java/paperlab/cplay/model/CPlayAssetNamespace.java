package paperlab.cplay.model;

public enum CPlayAssetNamespace {
    COMPOSITION(0, 'c'),
    SEQUENCE(1, 's');

    private final int index;
    private final char identifier;

    CPlayAssetNamespace(int index, char identifier) {
        this.index = index;
        this.identifier = identifier;
    }

    public int getIndex() { return index; }
    public char getIdentifier() { return identifier; }

    public static CPlayAssetNamespace fromIndex(int index) {
        return (index == 0) ? COMPOSITION : SEQUENCE;
    }

    public static CPlayAssetNamespace fromIdentifier(char c) {
        return (c == 'c' || c == 'C') ? COMPOSITION : SEQUENCE;
    }
}
