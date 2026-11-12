package org.apache.poi.poifs.nio;

import java.io.IOException;
import java.nio.ByteBuffer;

/** Android platform hook: do not reflect into desktop JVM buffer-cleaner APIs. */
public final class CleanerUtil {
    public static final boolean UNMAP_SUPPORTED = false;
    public static final String UNMAP_NOT_SUPPORTED_REASON = "Not supported on Android";

    private CleanerUtil() {}

    public static BufferCleaner getCleaner() { return null; }

    public interface BufferCleaner {
        void freeBuffer(ByteBuffer buffer) throws IOException;
    }
}
