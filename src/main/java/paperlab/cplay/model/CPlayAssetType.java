package paperlab.cplay.model;

public enum CPlayAssetType {
    COMPOSITION(0, ".gsa", "Composition"),
    SEQUENCE(1, ".gsa", "Sequence");

    private final int index;
    private final String extension;
    private final String displayName;

    CPlayAssetType(int index, String extension, String displayName) {
        this.index = index;
        this.extension = extension;
        this.displayName = displayName;
    }

    public int getIndex() { return index; }
    public String getExtension() { return extension; }
    public String getDisplayName() { return displayName; }

    public static CPlayAssetType fromIndex(int index) {
        return (index == 0) ? COMPOSITION : (index == 1 ? SEQUENCE : null);
    }
}
