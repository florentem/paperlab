package com.g4mesoft.captureplayback.common.asset;

import java.io.IOException;

import com.g4mesoft.util.GSDecodeBuffer;
import com.g4mesoft.util.GSEncodeBuffer;

public class GSPlayerCacheEntry {

	private final String name;
	
	public GSPlayerCacheEntry(String name) {
		if (name == null)
			throw new IllegalArgumentException("name is null");
		this.name = name;
	}
	
	public String getName() {
		return name;
	}
	
	public static GSPlayerCacheEntry read(GSDecodeBuffer buf) throws IOException {
		return new GSPlayerCacheEntry(buf.readString());
	}
	
	public static void write(GSEncodeBuffer buf, GSPlayerCacheEntry entry) throws IOException {
		buf.writeString(entry.name);
	}
}
