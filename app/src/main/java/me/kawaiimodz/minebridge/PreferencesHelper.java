package me.kawaiimodz.minebridge;

import android.content.Context;
import android.content.SharedPreferences;

public class PreferencesHelper {
    static {
        try {
            System.loadLibrary("minebridge");
        } catch (Throwable ignored) {}
    }

    private native String getDefaultPasswordNative();

    private static final String PREF_NAME = "minebridge_prefs";
    private static final String KEY_SERVER_IP = "server_ip";
    private static final String KEY_SERVER_PORT = "server_port";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_PASSWORD = "password";
    private static final String KEY_AUTO_CONNECT = "auto_connect";

    private final SharedPreferences prefs;

    public PreferencesHelper(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public void saveSettings(String serverIp, int serverPort, String username, String password, boolean autoConnect) {
        prefs.edit()
                .putString(KEY_SERVER_IP, serverIp != null ? serverIp.trim() : "")
                .putInt(KEY_SERVER_PORT, serverPort)
                .putString(KEY_USERNAME, username != null ? username.trim() : "")
                .putString(KEY_PASSWORD, password != null ? password : "")
                .putBoolean(KEY_AUTO_CONNECT, autoConnect)
                .apply();
    }

    public String getServerIp() {
        return prefs.getString(KEY_SERVER_IP, "");
    }

    public int getServerPort() {
        return prefs.getInt(KEY_SERVER_PORT, 24710);
    }

    public String getUsername() {
        return prefs.getString(KEY_USERNAME, "");
    }

    public String getPassword() {
        String savedPass = prefs.getString(KEY_PASSWORD, "");
        if (savedPass.isEmpty()) {
            try {
                return getDefaultPasswordNative();
            } catch (Throwable ignored) {
                return "minebridge123";
            }
        }
        return savedPass;
    }

    public boolean isAutoConnect() {
        return prefs.getBoolean(KEY_AUTO_CONNECT, false);
    }

    public boolean isConfigured() {
        return !getServerIp().isEmpty();
    }
}