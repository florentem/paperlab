package paperlab.cplay.model;

import java.security.SecureRandom;
import java.util.Objects;
import java.util.Random;

public final class CPlayAssetHandle implements Comparable<CPlayAssetHandle> {
    private static final String BASE36_CHARS = "0123456789abcdefghijklmnopqrstuvwxyz";
    private static final Random RANDOM = new SecureRandom();

    private final CPlayAssetNamespace namespace;
    private final String handle;

    public CPlayAssetHandle(CPlayAssetNamespace namespace, String handle) {
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.handle = Objects.requireNonNull(handle, "handle").toLowerCase();
    }

    public CPlayAssetNamespace getNamespace() { return namespace; }
    public String getHandle() { return handle; }

    public static CPlayAssetHandle random(CPlayAssetNamespace namespace, int length) {
        final StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(BASE36_CHARS.charAt(RANDOM.nextInt(BASE36_CHARS.length())));
        }
        return new CPlayAssetHandle(namespace, sb.toString());
    }

    public static CPlayAssetHandle parse(String s) {
        final int colon = s.indexOf(':');
        if (colon > 0) {
            char prefix = s.charAt(0);
            return new CPlayAssetHandle(CPlayAssetNamespace.fromIdentifier(prefix), s.substring(colon + 1));
        }
        return new CPlayAssetHandle(CPlayAssetNamespace.COMPOSITION, s);
    }

    @Override
    public String toString() {
        return namespace.getIdentifier() + ":" + handle;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CPlayAssetHandle that)) return false;
        return namespace == that.namespace && handle.equals(that.handle);
    }

    @Override
    public int hashCode() {
        return 31 * namespace.hashCode() + handle.hashCode();
    }

    @Override
    public int compareTo(CPlayAssetHandle o) {
        int cmp = Integer.compare(namespace.getIndex(), o.namespace.getIndex());
        return (cmp != 0) ? cmp : handle.compareTo(o.handle);
    }
}
