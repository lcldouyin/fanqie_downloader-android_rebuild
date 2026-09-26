package com.example.fqdownloader.security;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.fqdownloader.MainActivity;
import com.example.fqdownloader.R;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

public class ActivationActivity extends AppCompatActivity {

    private static final String TAG = "ActivationActivity";
    private EditText etCode;
    private Button btnActivate, btnScan;
    private TextView tvStatus, tvProgress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_activation);

        etCode = findViewById(R.id.et_activation_code);
        btnActivate = findViewById(R.id.btn_activate);
        btnScan = findViewById(R.id.btn_scan_qr);
        tvStatus = findViewById(R.id.tv_status);
        tvProgress = findViewById(R.id.tv_progress);

        btnScan.setOnClickListener(v -> {
            new IntentIntegrator(this)
                    .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                    .setPrompt("扫描激活码二维码")
                    .setBeepEnabled(false)
                    .initiateScan();
        });

        btnActivate.setOnClickListener(v -> {
            String code = etCode.getText().toString().trim();
            if (code.isEmpty()) {
                Toast.makeText(this, "请输入或扫描激活码", Toast.LENGTH_SHORT).show();
                return;
            }
            btnActivate.setEnabled(false);
            tvStatus.setText("验证中...");
            tvProgress.setVisibility(android.view.View.VISIBLE);
            tvProgress.setText("");

            new Thread(() -> {
                Activation.AuthorizedUser result = Activation.verify(this, code, message -> {
                    Log.i(TAG, "进度: " + message);
                    runOnUiThread(() -> tvProgress.setText(message));
                });

                runOnUiThread(() -> {
                    btnActivate.setEnabled(true);
                    if (result != null) {
                        tvProgress.setText("");
                        String displayUser = (result.user != null && !result.user.isEmpty())
                                ? "授权给 " + result.user : "";
                        tvStatus.setText("激活成功！" + displayUser);
                        Toast.makeText(this, "激活成功", Toast.LENGTH_SHORT).show();
                        startActivity(new Intent(this, MainActivity.class));
                        finish();
                    } else {
                        tvProgress.setText("");
                        tvStatus.setText("激活码无效");
                        new AlertDialog.Builder(this)
                                .setTitle("激活失败")
                                .setMessage("激活码无效，请联系开发者获取有效的激活码")
                                .setPositiveButton("确定", null)
                                .show();
                    }
                });
            }).start();
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result != null && result.getContents() != null) {
            etCode.setText(result.getContents().trim());
        }
    }
}
