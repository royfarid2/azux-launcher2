package com.azux.launcher.core;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** Azux's own account store: one small json per account. Offline accounts for now. */
public final class Accounts {
    private Accounts() { }

    public static final Pattern NAME_OK = Pattern.compile("[A-Za-z0-9_]{3,16}");

    public static final class Account {
        public final String name, uuid, type;
        public Account(String name, String uuid, String type) { this.name = name; this.uuid = uuid; this.type = type; }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("azux", Context.MODE_PRIVATE);
    }

    public static String offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)).toString();
    }

    public static List<Account> list(Context c) {
        List<Account> out = new ArrayList<>();
        File[] files = Paths.accounts(c).listFiles();
        if (files == null) return out;
        Arrays.sort(files);
        for (File f : files) {
            if (!f.getName().endsWith(".json")) continue;
            try {
                JSONObject o = new JSONObject(Net.readText(f));
                out.add(new Account(o.getString("name"), o.getString("uuid"), o.optString("type", "offline")));
            } catch (Exception ignored) { }
        }
        return out;
    }

    public static Account get(Context c, String name) {
        for (Account a : list(c)) if (a.name.equals(name)) return a;
        return null;
    }

    public static Account current(Context c) {
        Account a = get(c, prefs(c).getString("account", ""));
        if (a != null) return a;
        List<Account> all = list(c);
        return all.isEmpty() ? null : all.get(0);
    }

    public static void setCurrent(Context c, String name) {
        prefs(c).edit().putString("account", name).apply();
    }

    public static Account addOffline(Context c, String name) throws Exception {
        if (!NAME_OK.matcher(name).matches()) throw new Exception("Name: 3-16 letters, digits or _");
        JSONObject o = new JSONObject();
        o.put("name", name);
        o.put("uuid", offlineUuid(name));
        o.put("type", "offline");
        Net.writeText(new File(Paths.accounts(c), name + ".json"), o.toString());
        setCurrent(c, name);
        return new Account(name, o.getString("uuid"), "offline");
    }

    public static void remove(Context c, String name) {
        new File(Paths.accounts(c), name + ".json").delete();
    }
}
