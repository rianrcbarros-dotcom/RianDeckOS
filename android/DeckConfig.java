package br.com.rian.deck;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

final class DeckConfig {
    static final class Button {
        final String label, icon, action, folder, color;
        Button(String label, String icon, String action, String folder, String color) {
            this.label = label; this.icon = icon; this.action = action; this.folder = folder; this.color = color;
        }
    }
    static final class Page {
        final String id;
        final List<Button> buttons = new ArrayList<>();
        Page(JSONObject entry) {
            id = entry.optString("id");
            JSONArray items = entry.optJSONArray("buttons");
            if (items == null) return;
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                buttons.add(new Button(item.optString("label"), item.optString("icon"), item.optString("action"),
                    item.isNull("folder") ? "" : item.optString("folder"), item.optString("color", "graphite")));
            }
        }
    }
    final String active;
    final boolean automatic;
    final int revision;
    final List<Page> pages = new ArrayList<>();
    final JSONArray profiles, macros, history;

    DeckConfig(JSONObject value) {
        active = value.optString("active");
        automatic = value.optBoolean("auto");
        revision = value.optInt("revision");
        profiles = array(value, "profiles");
        macros = array(value, "macros");
        history = array(value, "history");
        JSONArray entries = array(value, "pages");
        for (int i = 0; i < entries.length(); i++) {
            JSONObject item = entries.optJSONObject(i);
            if (item != null) pages.add(new Page(item));
        }
    }

    boolean confirmation(String id) {
        for (int i = 0; i < macros.length(); i++) {
            JSONObject macro = macros.optJSONObject(i);
            if (macro != null && id.equals(macro.optString("id"))) return macro.optBoolean("confirm");
        }
        return false;
    }

    private static JSONArray array(JSONObject data, String key) {
        JSONArray result = data.optJSONArray(key);
        return result == null ? new JSONArray() : result;
    }
}
