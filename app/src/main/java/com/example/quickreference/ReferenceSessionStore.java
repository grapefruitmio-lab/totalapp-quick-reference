package com.example.quickreference;

import android.content.Context;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Local, content-bearing research provenance. This is intentionally separate
 * from privacy-bounded diagnostics. JSONL is used so future schema versions can
 * be streamed/exported without requiring a database migration first.
 */
public final class ReferenceSessionStore {
    private final File file;
    private final String sessionId = UUID.randomUUID().toString();
    private final String version;
    public ReferenceSessionStore(Context context, String version) {
        this.version = version;
        File dir = new File(context.getFilesDir(), "reference_sessions");
        if (!dir.exists()) dir.mkdirs();
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        file = new File(dir, "reference_session_" + ts + "_" + sessionId.substring(0,8) + ".jsonl");
    }
    public synchronized void event(String type, String k1, String v1, String k2, String v2) {
        try {
            JSONObject o = new JSONObject();
            o.put("schema", "quickreference.reference-event/0.1");
            o.put("session_id", sessionId);
            o.put("event_id", UUID.randomUUID().toString());
            o.put("timestamp", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(new Date()));
            o.put("app_version", version);
            o.put("event_type", type);
            o.put("provenance", "USER_ACTION");
            if (k1 != null && v1 != null) o.put(k1, v1);
            if (k2 != null && v2 != null) o.put(k2, v2);
            try (OutputStream out = new FileOutputStream(file, true)) {
                out.write((o.toString()+"\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) { }
    }
    public File getFile() { return file; }
    public String getSessionId() { return sessionId; }
}
