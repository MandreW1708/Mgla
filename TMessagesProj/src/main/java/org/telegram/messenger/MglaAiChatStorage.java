package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.UUID;

/** Small, account-scoped and deliberately bounded persistent store for AI chats. */
public final class MglaAiChatStorage {
    private static final String PREFS = "mgla_config";
    private static final String KEY_PREFIX = "ai_chats_v2_";
    private static final int MAX_CHATS = 40;
    private static final int MAX_MESSAGES = 100;

    public static final class Message {
        public String role; // user | assistant | system
        public String text;
        public long date;

        public Message(String role, String text, long date) {
            this.role = role;
            this.text = text;
            this.date = date;
        }
    }

    public static final class Chat {
        public String id;
        public String title;
        public String provider;
        public String model;
        public long updatedAt;
        public final ArrayList<Message> messages = new ArrayList<>();

        Chat() { }
    }

    private MglaAiChatStorage() { }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized ArrayList<Chat> getChats(int account) {
        ArrayList<Chat> result = new ArrayList<>();
        try {
            JSONArray chats = new JSONArray(prefs().getString(KEY_PREFIX + account, "[]"));
            for (int i = 0; i < chats.length(); i++) {
                JSONObject source = chats.optJSONObject(i);
                if (source == null) continue;
                Chat chat = new Chat();
                chat.id = source.optString("id");
                chat.title = source.optString("title", "Новый чат");
                chat.provider = source.optString("provider", "openrouter");
                chat.model = source.optString("model", "");
                chat.updatedAt = source.optLong("updated");
                JSONArray messages = source.optJSONArray("messages");
                if (messages != null) for (int j = 0; j < messages.length(); j++) {
                    JSONObject m = messages.optJSONObject(j);
                    if (m != null && !TextUtils.isEmpty(m.optString("text"))) {
                        chat.messages.add(new Message(m.optString("role", "assistant"), m.optString("text"), m.optLong("date")));
                    }
                }
                if (!TextUtils.isEmpty(chat.id)) result.add(chat);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        Collections.sort(result, (a, b) -> Long.compare(b.updatedAt, a.updatedAt));
        return result;
    }

    public static synchronized Chat newChat(String provider, String model) {
        Chat chat = new Chat();
        chat.id = UUID.randomUUID().toString();
        chat.title = "Новый чат";
        chat.provider = provider;
        chat.model = model;
        chat.updatedAt = System.currentTimeMillis();
        return chat;
    }

    public static synchronized void save(int account, Chat chat) {
        if (chat == null || TextUtils.isEmpty(chat.id)) return;
        chat.updatedAt = System.currentTimeMillis();
        if (!chat.messages.isEmpty()) {
            String first = null;
            for (Message m : chat.messages) if ("user".equals(m.role) && !TextUtils.isEmpty(m.text)) { first = m.text; break; }
            if (first != null) chat.title = first.replace('\n', ' ').trim();
            if (chat.title.length() > 52) chat.title = chat.title.substring(0, 52) + "…";
        }
        ArrayList<Chat> chats = getChats(account);
        for (int i = chats.size() - 1; i >= 0; i--) if (chat.id.equals(chats.get(i).id)) chats.remove(i);
        while (chat.messages.size() > MAX_MESSAGES) chat.messages.remove(0);
        chats.add(0, chat);
        while (chats.size() > MAX_CHATS) chats.remove(chats.size() - 1);
        JSONArray out = new JSONArray();
        try {
            for (Chat c : chats) {
                JSONObject item = new JSONObject();
                item.put("id", c.id); item.put("title", c.title); item.put("provider", c.provider);
                item.put("model", c.model); item.put("updated", c.updatedAt);
                JSONArray messages = new JSONArray();
                for (Message m : c.messages) {
                    JSONObject msg = new JSONObject();
                    msg.put("role", m.role); msg.put("text", m.text); msg.put("date", m.date);
                    messages.put(msg);
                }
                item.put("messages", messages); out.put(item);
            }
        } catch (Throwable e) { FileLog.e(e); }
        prefs().edit().putString(KEY_PREFIX + account, out.toString()).apply();
    }

    public static synchronized void delete(int account, String id) {
        ArrayList<Chat> chats = getChats(account);
        for (int i = chats.size() - 1; i >= 0; i--) if (id.equals(chats.get(i).id)) chats.remove(i);
        JSONArray out = new JSONArray();
        try { for (Chat c : chats) out.put(toJson(c)); } catch (Throwable ignore) { }
        prefs().edit().putString(KEY_PREFIX + account, out.toString()).apply();
    }

    private static JSONObject toJson(Chat c) throws Exception {
        JSONObject o = new JSONObject(); o.put("id", c.id); o.put("title", c.title); o.put("provider", c.provider); o.put("model", c.model); o.put("updated", c.updatedAt);
        JSONArray m = new JSONArray(); for (Message x : c.messages) { JSONObject v = new JSONObject(); v.put("role", x.role); v.put("text", x.text); v.put("date", x.date); m.put(v); } o.put("messages", m); return o;
    }
}
