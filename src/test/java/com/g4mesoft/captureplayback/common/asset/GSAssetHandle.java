package com.g4mesoft.captureplayback.common.asset;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Random;
import java.util.function.Predicate;

import com.g4mesoft.util.GSDecodeBuffer;
import com.g4mesoft.util.GSEncodeBuffer;

public class GSAssetHandle implements Comparable<GSAssetHandle> {

	/* Note: must be greater than length of Integer.toString(Integer.MAX_VALUE) */
	private static final int HANDLE_MAX_LENGTH = 20;
	
	private static final int  BASE36_COUNT = 36;
	private static final int  BASE36_POW_6 = BASE36_COUNT * BASE36_COUNT * BASE36_COUNT *
	                                         BASE36_COUNT * BASE36_COUNT * BASE36_COUNT;
	private static final long BASE36_POW_12 = (long)BASE36_POW_6 * BASE36_POW_6;

	private static final char[] BASE36_ALPHABET;
	private static final Random secureRandom = new SecureRandom();
	
	public static final char NAMESPACE_SEPARATOR = ':';
	
	static {
		// Generate alphabet in order 0-9, a-z
		BASE36_ALPHABET = new char[BASE36_COUNT];
		int i = 0;
		for (char c = '0'; c <= '9'; c++)
			BASE36_ALPHABET[i++] = c;
		for (char c = 'a'; c <= 'z'; c++)
			BASE36_ALPHABET[i++] = c;
		//assert i == BASE36_COUNT
	}
	
	private final GSEAssetNamespace namespace;
	private final String handle;
	
	private String identifierCache;

	public GSAssetHandle(GSEAssetNamespace namespace, String handle) {
		if (namespace == null)
			throw new IllegalArgumentException("namespace is null!");
		// Note: Intentional null-pointer exception
		if (handle.isEmpty())
			throw new IllegalArgumentException("Handle is empty!");
		if (handle.length() > HANDLE_MAX_LENGTH)
			throw new IllegalArgumentException("Handle length exceeds maximum!");
		if (!isBase36OrUnderscore(handle))
			throw new IllegalArgumentException("Handle contains non-base36/underscore characters!");
		this.namespace = namespace;
		this.handle = handle;
		// Cache for #toString() method
		identifierCache = null;
	}

	private static boolean isBase36OrUnderscore(String handle) {
		for (int i = 0; i < handle.length(); i++) {
			if (!isBase36OrUnderscore(handle.charAt(i)))
				return false;
		}
		return true;
	}

	private static boolean isBase36OrUnderscore(char c) {
		return isBase36(c) || c == '_';
	}
	
	private static boolean isBase36(char c) {
		if ('0' <= c && c <= '9')
			return true;
		if ('a' <= c && c <= 'z')
			return true;
		return false;
	}
	
	public static GSAssetHandle randomBase36(GSEAssetNamespace namespace, int length) {
		if (length <= 0)
			throw new IllegalArgumentException("length must be positive!");
		if (length > HANDLE_MAX_LENGTH)
			throw new IllegalArgumentException("length must be at most " + HANDLE_MAX_LENGTH);
		
		char[] result = new char[length];
		
		int i = 0;
		while (i < length) {
			long value = secureRandom.nextLong(BASE36_POW_12);
			// Convert value to the base36 alphabet.
			for (int j = 0; j < 12 && i < length; j++) {
				result[i++] = BASE36_ALPHABET[(int)(value % BASE36_COUNT)];
				value /= BASE36_COUNT;
			}
		}
		
		return new GSAssetHandle(namespace, new String(result));
	}
	
	public static GSAssetHandle randomBase36Unique(GSEAssetNamespace namespace, int length, Predicate<GSAssetHandle> existsPred) {
		GSAssetHandle result;
		do {
			result = randomBase36(namespace, length);
		} while (existsPred.test(result));
		
		return result;
	}
	
	public static final GSAssetHandle fromName(GSEAssetNamespace namespace, String name, String suffix) {
		StringBuilder sb = new StringBuilder(HANDLE_MAX_LENGTH);
		// Build handle from name. Symbols are replaced with the
		// underscore character ('_'), where two consecutive symbols
		// corresponds to a single underscore.
		for (int i = 0; i < name.length() && sb.length() < HANDLE_MAX_LENGTH - suffix.length(); i++) {
			char c = Character.toLowerCase(name.charAt(i));
			if (isBase36(c)) {
				sb.append(c);
			} else {
				// Replace non-base36 char by underscore.
				if (!sb.isEmpty() && sb.charAt(sb.length() - 1) != '_')
					sb.append('_');
			}
		}
		// name is empty or only contains non-base36 characters.
		if (sb.isEmpty())
			sb.append('_');
		// Finally, append the suffix
		sb.append(suffix);
		return new GSAssetHandle(namespace, sb.toString());
	}
	
	public static GSAssetHandle fromNameUnique(GSEAssetNamespace namespace, String name, Predicate<GSAssetHandle> existsPred) {
		int i = 1;
		GSAssetHandle result;
		do {
			String suffix = (i > 1 || name.isEmpty()) ? Integer.toString(i) : "";
			result = fromName(namespace, name, suffix);
			i++;
			// In the (very) unlikely case of overflow
			if (i < 0)
				throw new IllegalStateException("Unable to create unique handle");
		} while (existsPred.test(result));
		
		return result;
	}
	
	public static GSAssetHandle fromString(String identifier) {
		// The format of the identifier is:
		//     <namespace>:<handle>
		// where the namespace is a single character.
		if (identifier.length() < 2 || identifier.charAt(1) != NAMESPACE_SEPARATOR)
			throw new IllegalArgumentException("Identifier does not specify a namespace!");
		// Decode the namespace
		GSEAssetNamespace namespace = GSEAssetNamespace.fromIdentifier(identifier.charAt(0));
		if (namespace == null)
			throw new IllegalArgumentException("Unknown namespace '" + identifier.charAt(0) + "'.");
		// The remainder (after separator) is the handle.
		return new GSAssetHandle(namespace, identifier.substring(2));
	}
	
	public GSEAssetNamespace getNamespace() {
		return namespace;
	}
	
	public String getHandle() {
		return handle;
	}
	
	@Override
	public int hashCode() {
		int hash = 0;
		hash = 31 * hash + namespace.hashCode();
		hash = 31 * hash + handle.hashCode();
		return hash;
	}
	
	@Override
	public boolean equals(Object obj) {
		if (obj == this)
			return true;
		if (obj instanceof GSAssetHandle) {
			GSAssetHandle other = (GSAssetHandle)obj;
			if (namespace != other.namespace)
				return false;
			return handle.equals(other.handle);
		}
		return false;
	}
	
	@Override
	public int compareTo(GSAssetHandle other) {
		if (namespace != other.namespace)
			return namespace.getIndex() < other.namespace.getIndex() ? -1 : 1;
		return handle.compareTo(other.handle);
	}
	
	@Override
	public String toString() {
		if (identifierCache == null) {
			StringBuilder sb = new StringBuilder(2 + handle.length());
			sb.append(namespace.getIdentifier());
			sb.append(NAMESPACE_SEPARATOR);
			sb.append(handle);
			identifierCache = sb.toString();
		}
		return identifierCache;
	}
	
	public static GSAssetHandle read(GSDecodeBuffer buf) throws IOException {
		GSEAssetNamespace namespace = GSEAssetNamespace.fromIndex(buf.readUnsignedByte());
		if (namespace == null)
			throw new IllegalArgumentException("Unknown namespace");
		String handle = buf.readString(HANDLE_MAX_LENGTH);
		return new GSAssetHandle(namespace, handle);
	}
	
	public static void write(GSEncodeBuffer buf, GSAssetHandle handle) throws IOException {
		buf.writeUnsignedByte((short)handle.namespace.getIndex());
		buf.writeString(handle.handle);
	}
}
