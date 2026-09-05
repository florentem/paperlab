package paperlab.cplay.protocol;

public final class CPlayProtocol {

    public static final String CHANNEL = "minecraft:mod/g4mespeed";

    public static final int CORE_UID = 0x434F5245; // "CORE"
    public static final int CAPL_UID = 0x4341504C; // "CAPL"

    public static final int PACKET_CORE_CONNECTION = 10;

    public static final int PACKET_CAPL_SESSION_REQUEST = 10;
    public static final int PACKET_CAPL_SESSION_START = 11;
    public static final int PACKET_CAPL_SESSION_STOP = 12;
    public static final int PACKET_CAPL_SESSION_DELTAS = 13;

    public static final int PACKET_CAPL_ASSET_HISTORY = 14;
    public static final int PACKET_CAPL_ASSET_INFO_CHANGED = 15;
    public static final int PACKET_CAPL_ASSET_INFO_REMOVED = 16;
    public static final int PACKET_CAPL_CREATE_ASSET = 17;
    public static final int PACKET_CAPL_DELETE_ASSET = 18;
    public static final int PACKET_CAPL_IMPORT_ASSET = 19;
    public static final int PACKET_CAPL_REQUEST_ASSET = 20;
    public static final int PACKET_CAPL_ASSET_REQUEST_RESPONSE = 21;

    public static final int PACKET_CAPL_PLAYER_CACHE = 22;
    public static final int PACKET_CAPL_PLAYER_CACHE_ADDED = 23;
    public static final int PACKET_CAPL_PLAYER_CACHE_REMOVED = 24;

    public static final int PACKET_CAPL_COLLABORATOR = 25;

    public static long makePacketId(int extensionUid, int packetSubId) {
        return ((long) extensionUid << 32L) | ((long) packetSubId & 0xFFFFFFFFL);
    }

    private CPlayProtocol() {
    }
}
