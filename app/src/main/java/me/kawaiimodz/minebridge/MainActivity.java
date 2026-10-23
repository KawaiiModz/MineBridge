package me.kawaiimodz.minebridge;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.VpnService;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import me.kawaiimodz.minebridge.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {

    private enum ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED
    }

    static {
        try {
            System.loadLibrary("minebridge");
        } catch (Throwable ignored) {}
    }

    private native boolean verifySignatureNative(Context context);

    private ActivityMainBinding binding;
    private PreferencesHelper prefsHelper;
    private ConnectionState currentState = ConnectionState.DISCONNECTED;
    private long lastConnectClickTime = 0;
    private boolean hasAutoConnectedThisSession = false;
    private boolean isSensitiveDataVisible = false; // Masked by default
    private String currentVirtualIp = "--.--.--.--";

    private String maskString(String input, boolean isUsername) {
        if (input == null || input.isEmpty() || input.equals(getString(R.string.not_configured))) {
            return getString(R.string.not_configured);
        }

        if (isSensitiveDataVisible) {
            return input;
        }

        if (isUsername) {
            int len = input.length();
            if (len <= 2) {
                return "**";
            } else if (len <= 4) {
                return input.charAt(0) + "**" + input.charAt(len - 1);
            } else {
                return input.substring(0, 3) + "***" + input.substring(len - 2);
            }
        } else {
            if (input.contains(":")) {
                int colonIdx = input.indexOf(':');
                String port = input.substring(colonIdx);
                return "***.***.***.***" + port;
            } else {
                return "***.***.***.***";
            }
        }
    }

    private final BroadcastReceiver vpnReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !MineBridgeVpnService.ACTION_VPN_STATE_CHANGED.equals(intent.getAction())) {
                return;
            }

            int state = intent.getIntExtra(MineBridgeVpnService.EXTRA_STATE, MineBridgeVpnService.STATE_DISCONNECTED);
            switch (state) {
                case MineBridgeVpnService.STATE_CONNECTING:
                    updateUIForState(ConnectionState.CONNECTING);
                    break;

                case MineBridgeVpnService.STATE_CONNECTED:
                    if (currentState == ConnectionState.DISCONNECTED) {
                        // Ignore late PONG if user already clicked Disconnect
                        break;
                    }
                    String virtualIp = intent.getStringExtra(MineBridgeVpnService.EXTRA_VIRTUAL_IP);
                    int ping = intent.getIntExtra(MineBridgeVpnService.EXTRA_PING, -1);

                    if (virtualIp != null && !virtualIp.isEmpty()) {
                        currentVirtualIp = virtualIp;
                        binding.tvVirtualIp.setText(maskString(currentVirtualIp, false));
                    }
                    if (ping > 0) {
                        String pingText = ping + " ms";
                        binding.tvPing.setText(pingText);
                    }
                    updateUIForState(ConnectionState.CONNECTED);
                    break;

                case MineBridgeVpnService.STATE_FAILED:
                    String errorMsg = intent.getStringExtra(MineBridgeVpnService.EXTRA_ERROR);
                    if (errorMsg != null && !errorMsg.isEmpty()) {
                        Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show();
                    }
                    updateUIForState(ConnectionState.DISCONNECTED);
                    break;

                case MineBridgeVpnService.STATE_DISCONNECTED:
                default:
                    updateUIForState(ConnectionState.DISCONNECTED);
                    break;
            }
        }
    };

    private final ActivityResultLauncher<Intent> settingsLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> updateServerInfo()
    );

    private final ActivityResultLauncher<Intent> vpnPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK) {
                    startVpnService();
                } else {
                    updateUIForState(ConnectionState.DISCONNECTED);
                    Toast.makeText(this, "VPN permission is required to connect!", Toast.LENGTH_SHORT).show();
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Security check: Verify APK integrity & signature via Native C++
        try {
            if (!verifySignatureNative(this)) {
                Toast.makeText(this, "Unauthorized APK modification detected!", Toast.LENGTH_LONG).show();
                finishAffinity();
                return;
            }
        } catch (Throwable ignored) {}

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Setup Toolbar with Hamburger Menu
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setHomeButtonEnabled(true);
        }
        binding.toolbar.setNavigationIcon(R.drawable.ic_menu);
        binding.toolbar.setNavigationOnClickListener(v -> binding.drawerLayout.openDrawer(androidx.core.view.GravityCompat.START));

        // Setup Navigation Drawer Item Click Listeners
        binding.navigationView.setNavigationItemSelectedListener(item -> {
            int id = item.getItemId();
            String url = "";
            if (id == R.id.nav_github) {
                url = "https://github.com/KawaiiModz";
            } else if (id == R.id.nav_discord) {
                url = "https://discord.com/users/1342351115383083038";
            } else if (id == R.id.nav_messenger) {
                url = "https://m.me/conekawa";
            } else if (id == R.id.nav_zalo) {
                url = "https://zalo.me/0337527772";
            } else if (id == R.id.nav_tiktok) {
                url = "https://www.tiktok.com/@kawaiinek210";
            }

            if (!url.isEmpty()) {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
                    startActivity(intent);
                } catch (Exception ignored) {}
            }
            binding.drawerLayout.closeDrawer(androidx.core.view.GravityCompat.START);
            return true;
        });

        prefsHelper = new PreferencesHelper(this);

        setupClickListeners();
        updateServerInfo();
        updateUIForState(ConnectionState.DISCONNECTED);

        checkAndTriggerAutoConnect();

        LocalBroadcastManager.getInstance(this).registerReceiver(
                vpnReceiver, new IntentFilter(MineBridgeVpnService.ACTION_VPN_STATE_CHANGED));
    }

    private void checkAndTriggerAutoConnect() {
        if (!hasAutoConnectedThisSession && prefsHelper.isConfigured() && prefsHelper.isAutoConnect()) {
            hasAutoConnectedThisSession = true;
            prepareAndStartVpn();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateServerInfo();
    }

    @Override
    protected void onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(vpnReceiver);
        super.onDestroy();
    }

    private void setupClickListeners() {
        binding.btnSettings.setOnClickListener(v -> {
            Intent intent = new Intent(this, SettingsActivity.class);
            settingsLauncher.launch(intent);
        });

        binding.btnToggleVisibility.setOnClickListener(v -> {
            isSensitiveDataVisible = !isSensitiveDataVisible;
            binding.btnToggleVisibility.setImageResource(isSensitiveDataVisible ? R.drawable.ic_eye : R.drawable.ic_eye_off);
            updateServerInfo();
            if (currentState == ConnectionState.CONNECTED) {
                binding.tvVirtualIp.setText(maskString(currentVirtualIp, false));
            }
        });

        binding.layoutPowerBtn.setOnClickListener(v -> toggleConnection());
    }

    private void updateServerInfo() {
        if (prefsHelper.isConfigured()) {
            String serverText = prefsHelper.getServerIp() + ":" + prefsHelper.getServerPort();
            binding.tvCurrentServer.setText(maskString(serverText, false));

            String rawUser = prefsHelper.getUsername();
            String userText = rawUser.isEmpty() ? getString(R.string.not_configured) : rawUser;
            binding.tvCurrentUsername.setText(maskString(userText, true));
        } else {
            binding.tvCurrentServer.setText(R.string.not_configured);
            binding.tvCurrentUsername.setText(R.string.not_configured);
        }
    }

    private void toggleConnection() {
        if (!prefsHelper.isConfigured()) {
            Toast.makeText(this, R.string.msg_configure_first, Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(this, SettingsActivity.class);
            settingsLauncher.launch(intent);
            return;
        }

        long now = System.currentTimeMillis();
        long timeSinceLastClick = now - lastConnectClickTime;
        if (timeSinceLastClick < 5000) {
            long remainingSec = (5000 - timeSinceLastClick) / 1000 + 1;
            Toast.makeText(this, "Please wait " + remainingSec + "s before trying again!", Toast.LENGTH_SHORT).show();
            return;
        }
        lastConnectClickTime = now;

        if (currentState == ConnectionState.DISCONNECTED) {
            prepareAndStartVpn();
        } else if (currentState == ConnectionState.CONNECTED || currentState == ConnectionState.CONNECTING) {
            stopVpnService();
        }
    }

    private void prepareAndStartVpn() {
        updateUIForState(ConnectionState.CONNECTING);

        Intent vpnIntent = VpnService.prepare(this);
        if (vpnIntent != null) {
            vpnPermissionLauncher.launch(vpnIntent);
        } else {
            startVpnService();
        }
    }

    private void startVpnService() {
        Intent intent = new Intent(this, MineBridgeVpnService.class);
        intent.putExtra("SERVER_IP", prefsHelper.getServerIp());
        intent.putExtra("SERVER_PORT", prefsHelper.getServerPort());
        intent.putExtra("USERNAME", prefsHelper.getUsername());
        intent.putExtra("PASSWORD", prefsHelper.getPassword());
        startService(intent);
    }

    private void stopVpnService() {
        updateUIForState(ConnectionState.DISCONNECTED);
        Intent intent = new Intent(this, MineBridgeVpnService.class);
        intent.setAction(MineBridgeVpnService.ACTION_DISCONNECT);
        startService(intent);
        stopService(intent);
    }

    private void updateUIForState(ConnectionState state) {
        this.currentState = state;

        switch (state) {
            case DISCONNECTED:
                binding.indicatorDot.setBackgroundResource(R.drawable.bg_indicator_disconnected);
                binding.tvStatus.setText(R.string.status_disconnected);
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.text_primary));

                binding.layoutPowerBtn.setBackgroundResource(R.drawable.bg_power_button_off);
                binding.ivPowerIcon.setColorFilter(ContextCompat.getColor(this, R.color.text_secondary));
                binding.tvBtnLabel.setText(R.string.btn_connect);
                binding.tvBtnLabel.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));

                binding.tvVirtualIp.setText("--.--.--.--");
                binding.tvPing.setText("-- ms");
                binding.tvPing.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
                break;

            case CONNECTING:
                binding.indicatorDot.setBackgroundResource(R.drawable.bg_indicator_connecting);
                binding.tvStatus.setText(R.string.status_connecting);
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_amber));

                binding.layoutPowerBtn.setBackgroundResource(R.drawable.bg_power_button_connecting);
                binding.ivPowerIcon.setColorFilter(ContextCompat.getColor(this, R.color.white));
                binding.tvBtnLabel.setText(R.string.btn_connecting);
                binding.tvBtnLabel.setTextColor(ContextCompat.getColor(this, R.color.accent_amber));

                binding.tvPing.setText("... ms");
                binding.tvPing.setTextColor(ContextCompat.getColor(this, R.color.accent_amber));
                break;

            case CONNECTED:
                binding.indicatorDot.setBackgroundResource(R.drawable.bg_indicator_connected);
                binding.tvStatus.setText(R.string.status_connected);
                binding.tvStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_green));

                binding.layoutPowerBtn.setBackgroundResource(R.drawable.bg_power_button_on);
                binding.ivPowerIcon.setColorFilter(ContextCompat.getColor(this, R.color.white));
                binding.tvBtnLabel.setText(R.string.btn_disconnect);
                binding.tvBtnLabel.setTextColor(ContextCompat.getColor(this, R.color.accent_green));
                break;
        }
    }
}