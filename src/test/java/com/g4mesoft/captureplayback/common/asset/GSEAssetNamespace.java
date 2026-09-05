package com.g4mesoft.captureplayback.common.asset;

public enum GSEAssetNamespace {

	GLOBAL("global", 'g', 0),
	WORLD("world", 'w', 1);

	private static final GSEAssetNamespace[] NAMESPACES;
	
	static {
		NAMESPACES = new GSEAssetNamespace[values().length];
		for (GSEAssetNamespace type : values())
			NAMESPACES[type.index] = type;
	}
	
	private final String name;
	private final char identifier;
	private final int index;
	
	private GSEAssetNamespace(String name, char identifier, int index) {
		this.name = name;
		this.identifier = identifier;
		this.index = index;
	}
	
	public String getName() {
		return name;
	}
	
	public char getIdentifier() {
		return identifier;
	}
	
	public int getIndex() {
		return index;
	}
	
	public static GSEAssetNamespace fromIndex(int index) {
		if (index < 0 || index >= NAMESPACES.length)
			return null;
		return NAMESPACES[index];
	}
	
	public static GSEAssetNamespace fromIdentifier(char c) {
		for (GSEAssetNamespace type : NAMESPACES) {
			if (type.getIdentifier() == c)
				return type;
		}
		return null;
	}
}
