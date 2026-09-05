package paperlab.cplay.model;

import java.security.SecureRandom;
import java.util.Objects;
import java.util.Random;
import java.util.function.Predicate;

public final class CPlayAssetHandle implements Comparable<CPlayAssetHandle> {
    public static final int HANDLE_MAX_LENGTH = 20;
    public static final char NAMESPACE_SEPARATOR = ':';

    private static final String BASE36_CHARS = "0123456789abcdefghijklmnopqrstuvwxyz";
    private static final Random RANDOM = new SecureRandom();

    private final CPlayAssetNamespace namespace;
    private final String handle;

    public CPlayAssetHandle(final CPlayAssetNamespace namespace, final String handle) {
        this.namespace = Objects.requireNonNull(namespace, "namespace");
        this.handle = Objects.requireNonNull(handle, "handle").toLowerCase();
        if (this.handle.isEmpty()) {
            throw new IllegalArgumentException("Handle is empty!");
        }
        if (this.handle.length() > HANDLE_MAX_LENGTH) {
            throw new IllegalArgumentException("Handle length exceeds maximum!");
        }
    }

    public CPlayAssetNamespace getNamespace() { return namespace; }
    public String getHandle() { return handle; }

    public static CPlayAssetHandle random(final CPlayAssetNamespace namespace, final int length) {
        final StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(BASE36_CHARS.charAt(RANDOM.nextInt(BASE36_CHARS.length())));
        }
        return new CPlayAssetHandle(namespace, sb.toString());
    }

    private static boolean isBase36(final char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z');
    }

    public static CPlayAssetHandle fromName(final CPlayAssetNamespace namespace, final String name, final String suffix) {
        final StringBuilder sb = new StringBuilder(HANDLE_MAX_LENGTH);
        final int maxNameLen = Math.max(0, HANDLE_MAX_LENGTH - suffix.length());
        for (int i = 0; i < name.length() && sb.length() < maxNameLen; i++) {
            final char c = Character.toLowerCase(name.charAt(i));
            if (isBase36(c)) {
                sb.append(c);
            } else {
                if (!sb.isEmpty() && sb.charAt(sb.length() - 1) != '_') {
                    sb.append('_');
                }
            }
        }
        if (sb.isEmpty()) {
            sb.append('_');
        }
        sb.append(suffix);
        return new CPlayAssetHandle(namespace, sb.toString());
    }

    public static CPlayAssetHandle fromNameUnique(final CPlayAssetNamespace namespace, final String name, final Predicate<CPlayAssetHandle> existsPred) {
        int i = 1;
        CPlayAssetHandle result;
        do {
            final String suffix = (i > 1 || name.isEmpty()) ? Integer.toString(i) : "";
            result = fromName(namespace, name, suffix);
            i++;
            if (i < 0) {
                throw new IllegalStateException("Unable to create unique handle");
            }
        } while (existsPred.test(result));
        return result;
    }

    public static CPlayAssetHandle parse(final String s) {
        if (s == null || s.trim().isEmpty()) {
            throw new IllegalArgumentException("Handle string cannot be null or empty");
        }
        final int colon = s.indexOf(NAMESPACE_SEPARATOR);
        if (colon > 0) {
            final char prefix = s.charAt(0);
            final CPlayAssetNamespace ns = CPlayAssetNamespace.fromIdentifier(prefix);
            if (ns == null) {
                throw new IllegalArgumentException("Unknown namespace prefix: " + prefix);
            }
            final String sub = s.substring(colon + 1);
            if (sub.isEmpty()) {
                throw new IllegalArgumentException("Handle part cannot be empty: " + s);
            }
            return new CPlayAssetHandle(ns, sub);
        }
        return new CPlayAssetHandle(CPlayAssetNamespace.GLOBAL, s);
    }

    @Override
    public String toString() {
        return namespace.getIdentifier() + ":" + handle;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (!(o instanceof CPlayAssetHandle that)) return false;
        return namespace == that.namespace && handle.equals(that.handle);
    }

    @Override
    public int hashCode() {
        return 31 * namespace.hashCode() + handle.hashCode();
    }

    @Override
    public int compareTo(final CPlayAssetHandle o) {
        final int cmp = Integer.compare(namespace.getIndex(), o.namespace.getIndex());
        return (cmp != 0) ? cmp : handle.compareTo(o.handle);
    }
}
