package org.telegram.custom.aicall;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.custom.utils.CustomLogger;
import org.telegram.messenger.ApplicationLoader;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Local JSON store for completed AI/Owner call logs (#nc-call-r) so the
 * dedicated Call History & Chat Replay UI works offline and independently
 * of Telegram's Saved Messages chat view.
 */
public final class AiCallHistoryStore {
    private static final String TAG = "AiCallHistoryStore";
    private static final String DIR_NAME = "ai_call_history";

    public static final class CallRecord {
        public String serialId = "";
        public long peerUserId = 0L;
        public String peerName = "Caller";
        public String direction = "INCOMING";
        public long startedAtMs = 0L;
        public long durationSec = 0L;
        public int handoverCount = 0;
        public String audioFilePath = "";
        public String detailedSummary = "";
        public List<CallContextMemory.ChatEntry> messages = new ArrayList<>();
    }

    private AiCallHistoryStore() {}

    private static File getDir() {
        File dir = new File(ApplicationLoader.getFilesDirFixed(), DIR_NAME);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    public static synchronized void saveRecord(CallRecord record) {
        if (record == null || record.serialId == null || record.serialId.trim().isEmpty()) {
            return;
        }
        try {
            JSONObject obj = new JSONObject();
            obj.put("serialId", record.serialId);
            obj.put("peerUserId", record.peerUserId);
            obj.put("peerName", record.peerName != null ? record.peerName : "Caller");
            obj.put("direction", record.direction != null ? record.direction : "INCOMING");
            obj.put("startedAtMs", record.startedAtMs);
            obj.put("durationSec", record.durationSec);
            obj.put("handoverCount", record.handoverCount);
            obj.put("audioFilePath", record.audioFilePath != null ? record.audioFilePath : "");
            obj.put("detailedSummary", record.detailedSummary != null ? record.detailedSummary : "");

            JSONArray arr = new JSONArray();
            if (record.messages != null) {
                for (CallContextMemory.ChatEntry e : record.messages) {
                    JSONObject m = new JSONObject();
                    m.put("role", e.role);
                    m.put("text", e.text);
                    m.put("ts", e.timestampMs);
                    arr.put(m);
                }
            }
            obj.put("messages", arr);

            File file = new File(getDir(), record.serialId + ".json");
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(obj.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable t) {
            CustomLogger.e(TAG, "Failed saving call record " + record.serialId, t);
        }
    }

    public static synchronized List<CallRecord> loadAllRecords() {
        List<CallRecord> list = new ArrayList<>();
        File dir = getDir();
        File[] files = dir.listFiles((d, name) -> name != null && name.endsWith(".json"));
        if (files == null) return list;

        for (File f : files) {
            try {
                String raw = readAll(f);
                if (raw.isEmpty()) continue;
                JSONObject obj = new JSONObject(raw);
                CallRecord r = new CallRecord();
                r.serialId = obj.optString("serialId", f.getName().replace(".json", ""));
                r.peerUserId = obj.optLong("peerUserId", 0L);
                r.peerName = obj.optString("peerName", "Caller");
                r.direction = obj.optString("direction", "INCOMING");
                r.startedAtMs = obj.optLong("startedAtMs", f.lastModified());
                r.durationSec = obj.optLong("durationSec", 0L);
                r.handoverCount = obj.optInt("handoverCount", 0);
                r.audioFilePath = obj.optString("audioFilePath", "");
                r.detailedSummary = obj.optString("detailedSummary", "");

                JSONArray arr = obj.optJSONArray("messages");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject m = arr.optJSONObject(i);
                        if (m != null) {
                            r.messages.add(new CallContextMemory.ChatEntry(
                                    m.optString("role", "CALLER"),
                                    m.optString("text", ""),
                                    m.optLong("ts", r.startedAtMs)
                            ));
                        }
                    }
                }
                list.add(r);
            } catch (Throwable t) {
                CustomLogger.e(TAG, "Failed reading call record " + f.getName(), t);
            }
        }
        Collections.sort(list, (a, b) -> Long.compare(b.startedAtMs, a.startedAtMs));
        return list;
    }

    public static synchronized void deleteRecord(String serialId) {
        if (serialId == null || serialId.trim().isEmpty()) return;
        File f = new File(getDir(), serialId + ".json");
        if (f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    private static String readAll(File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }
}
