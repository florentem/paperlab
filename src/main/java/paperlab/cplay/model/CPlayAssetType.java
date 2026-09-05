package paperlab.cplay.model;

public enum CPlayAssetType {
    SEQUENCE(0, ".gseq", "Sequence"),
    COMPOSITION(1, ".gcomp", "Composition");

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
        return (index == 0) ? SEQUENCE : COMPOSITION;
    }
}
