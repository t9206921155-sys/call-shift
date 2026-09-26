package io.github.up9cloud.td;

/** JNI ABI of the pinned up9cloud TDLib build. See assets/TDLIB-NOTICES.txt. */
public final class JsonClient {
    private JsonClient() {}
    public static void load() { System.loadLibrary("tdjson"); }
    public static native int td_create_client_id();
    public static native void td_send(int clientId, String request);
    public static native String td_receive(double timeout);
    public static native String td_execute(String request);
}
