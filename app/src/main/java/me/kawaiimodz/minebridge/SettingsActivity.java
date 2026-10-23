package me.kawaiimodz.minebridge;

import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import me.kawaiimodz.minebridge.databinding.ActivitySettingsBinding;

public class SettingsActivity extends AppCompatActivity {

    private ActivitySettingsBinding binding;
    private PreferencesHelper prefsHelper;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        prefsHelper = new PreferencesHelper(this);

        setupToolbar();
        loadExistingSettings();
        setupSaveButton();
    }

    private void setupToolbar() {
        binding.settingsToolbar.setNavigationOnClickListener(v -> finish());
    }

    private void loadExistingSettings() {
        if (binding.etServerIp != null) {
            binding.etServerIp.setText(prefsHelper.getServerIp());
        }
        if (binding.etServerPort != null) {
            binding.etServerPort.setText(String.valueOf(prefsHelper.getServerPort()));
        }
        if (binding.etUsername != null) {
            binding.etUsername.setText(prefsHelper.getUsername());
        }
        if (binding.etPassword != null) {
            binding.etPassword.setText(prefsHelper.getPassword());
        }
        if (binding.switchAutoConnect != null) {
            binding.switchAutoConnect.setChecked(prefsHelper.isAutoConnect());
        }
    }

    private void setupSaveButton() {
        binding.btnSave.setOnClickListener(v -> saveSettings());
    }

    private void saveSettings() {
        String serverIp = binding.etServerIp.getText() != null ? binding.etServerIp.getText().toString().trim() : "";
        String portStr = binding.etServerPort.getText() != null ? binding.etServerPort.getText().toString().trim() : "24710";
        String username = binding.etUsername.getText() != null ? binding.etUsername.getText().toString().trim() : "";
        String password = binding.etPassword.getText() != null ? binding.etPassword.getText().toString() : "";
        boolean autoConnect = binding.switchAutoConnect.isChecked();

        if (serverIp.isEmpty()) {
            binding.tilServerIp.setError(getString(R.string.msg_configure_first));
            return;
        } else {
            binding.tilServerIp.setError(null);
        }

        int port = 24710;
        try {
            port = Integer.parseInt(portStr);
        } catch (NumberFormatException ignored) {}

        prefsHelper.saveSettings(serverIp, port, username, password, autoConnect);

        Toast.makeText(this, R.string.msg_settings_saved, Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }
}