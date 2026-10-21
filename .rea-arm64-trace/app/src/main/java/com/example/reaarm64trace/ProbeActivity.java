package com.example.arm64trace;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.bytedance.mobsec.metasec.ml.MS;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class ProbeActivity extends Activity {
    private static final String TAG = "Arm64TraceProbe";
    private static final int BASE_METRICS_LENGTH = 19167;
    private static final int TREE_NODE_EVENT_COUNT_OFFSET = BASE_METRICS_LENGTH;
    private static final int TREE_NODE_EVENT_CAPACITY = 64;
    private static final int TREE_NODE_EVENT_WORD_COUNT = 56;
    private static final int EXPECTED_METRICS_LENGTH = TREE_NODE_EVENT_COUNT_OFFSET
            + 1 + TREE_NODE_EVENT_CAPACITY * TREE_NODE_EVENT_WORD_COUNT;
    private static final int[] CORE_NESTED_CALL_SITES = {
            0x16b91c, 0x16b998, 0x16ba2c, 0x16bc2c, 0x16bc9c, 0x16c188,
            0x16c888, 0x16cb40, 0x16d034, 0x16d248, 0x16d554
    };
    private static final int[] NESTED_METHOD_CALL_SITES = {
            0x15b31c, 0x15beb8, 0x15c328, 0x15c434, 0x15c480, 0x15c81c,
            0x15c898, 0x15cd38, 0x15cdb0, 0x15cf64, 0x15cfa4, 0x15d02c,
            0x15d074, 0x15d18c, 0x15d238, 0x15d6fc, 0x15db2c, 0x15de9c,
            0x15dfe4, 0x15e4dc, 0x15e524, 0x15eb14, 0x15eb60, 0x15ecc4,
            0x15f010, 0x15f18c, 0x15f7d4, 0x15fc78, 0x15fec0
    };
    private static final String URL =
            "https://example.invalid/api/reader/directory/detail?aid=1967&book_id=123";
    private static final String BASELINE_HEADERS =
            "Cookie\r\nstore-region=cn-zj; store-region-src=did; install_id=111111111111111\r\n"
                    + "User-Agent\r\ncom.dragon.read.oversea.gp/68132 (Linux; U; Android 10; zh_CN; "
                    + "OnePlus11; Build/V291IR;tt-ok/3.12.13.4-tiktok)\r\n"
                    + "Accept\r\napplication/json; charset=utf-8\r\n"
                    + "Accept-Encoding\r\ngzip\r\n"
                    + "x-xs-from-web\r\n0\r\n"
                    + "x-ss-req-ticket\r\n1700000000000\r\n"
                    + "x-reading-request\r\n1700000000000-123456789\r\n"
                    + "x-vc-bdturing-sdk-version\r\n3.7.2.cn\r\n"
                    + "lc\r\n101\r\n"
                    + "sdk-version\r\n2\r\n"
                    + "passport-sdk-version\r\n50564\r\n"
                    + "x-tt-store-region\r\ncn-zj\r\n"
                    + "x-tt-store-region-src\r\ndid";

    static {
        System.loadLibrary("traceprobe");
    }

    private static volatile boolean prepared;

    private static native boolean nativePrepare(String libraryPath);
    private static native Object[] nativeSign(
            String url, String headers, boolean traceCallState);

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        TextView status = new TextView(this);
        Button run = new Button(this);
        run.setText("Run synthetic signer");
        run.setEnabled(false);
        layout.addView(status);
        layout.addView(run);
        setContentView(layout);

        try {
            byte[] certificate;
            try (InputStream input = getAssets().open("ms_16777218.bin");
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                certificate = output.toByteArray();
            }
            MS.configure(certificate,
                    new java.io.File(getFilesDir(), ".msdata").getAbsolutePath());

            String library = new java.io.File(getApplicationInfo().nativeLibraryDir,
                    "libmetasec_ml.so").getAbsolutePath();
            prepared = nativePrepare(library);
            status.setText(prepared
                    ? "ARM64 library loaded. Run the synthetic request."
                    : "Library initialization failed; see logcat.");
            run.setEnabled(prepared);
            Log.i(TAG, prepared ? "READY" : "PREPARE_FAILED");
            record(this, prepared ? "PREPARE_OK\n" : "PREPARE_FAILED\n");
        } catch (Exception error) {
            Log.e(TAG, "Probe preparation failed", error);
            status.setText("Probe preparation failed; see logcat.");
            record(this, "PREPARE_EXCEPTION\n");
        }

        run.setOnClickListener(view -> runSyntheticSigner(this));
    }

    static void runSyntheticSigner(Context context) {
        if (!prepared) {
            Log.e(TAG, "Signer requested before successful preparation");
            record(context, "NOT_PREPARED\n");
            return;
        }
        Log.i(TAG, "Synthetic signer warm-up started");
        Object[] warmup = nativeSign(URL, "Warmup\r\nSynthetic", false);
        if (warmup == null || warmup.length != 2
                || !(warmup[0] instanceof long[])) {
            Log.e(TAG, "Synthetic signer warm-up failed");
            record(context, "WARMUP_FAILED\n");
            return;
        }
        Log.i(TAG, "Synthetic signer warm-up completed");
        StringBuilder observation = new StringBuilder();
        appendCase(observation, "baseline", URL, BASELINE_HEADERS);
        appendCase(observation, "baseline_repeat", URL, BASELINE_HEADERS);
        for (int repeat = 2; repeat <= 5; repeat++) {
            appendCase(observation, "baseline_repeat_" + repeat, URL, BASELINE_HEADERS);
        }
        appendCase(observation, "query_changed", URL + "&probe=one", BASELINE_HEADERS);
        appendCase(observation, "header_changed", URL,
                BASELINE_HEADERS.replace("x-xs-from-web\r\n0", "x-xs-from-web\r\n1"));
        appendCase(observation, "header_added", URL,
                BASELINE_HEADERS + "\r\nX-Probe\r\nsynthetic");
        appendCase(observation, "empty_headers", URL, "");
        appendCase(observation, "one_pair", URL, "K\r\nV");
        appendCase(observation, "one_token", URL, "K");
        appendCase(observation, "pair_with_trailing_delimiter", URL, "K\r\nV\r\n");
        record(context, observation.toString());
        Log.i(TAG, "Synthetic parser edge cases completed");
    }

    private static void appendCase(
            StringBuilder output, String name, String url, String headers) {
        Log.i(TAG, "Synthetic signer case started: " + name);
        Object[] nativeResult = nativeSign(url, headers, "baseline".equals(name));
        Log.i(TAG, "Synthetic signer case returned: " + name);
        if (nativeResult == null || nativeResult.length != 2
                || !(nativeResult[0] instanceof long[])) {
            Log.e(TAG, "Native probe returned an invalid record for " + name);
            output.append(name).append("_failed\n");
            return;
        }
        long[] result = (long[]) nativeResult[0];
        String signedOutput = (String) nativeResult[1];
        if (result.length != EXPECTED_METRICS_LENGTH) {
            Log.e(TAG, "Native probe returned an invalid metrics array for " + name);
            output.append(name).append("_failed\n");
            return;
        }
        output.append("case=").append(name)
                .append(" result_length=").append(result[0])
                .append(" split_input_length=").append(result[26])
                .append(" split_hits=").append(result[22])
                .append(" pair_calls=").append(result[29])
                .append(" empty_guard=").append(result[32]).append('/')
                .append(result[33])
                .append(" odd_count_guard=").append(result[34]).append('/')
                .append(result[35])
                .append(" status_guard=").append(result[36]).append('/')
                .append(result[37])
                .append(" algorithm_calls=").append(result[38])
                .append(" rand_plt_calls=").append(result[121])
                .append(" rand_return_0x8d840=").append(result[122])
                .append(" rand_return_0x13f8d4=").append(result[123])
                .append(" rand_return_0x1c2134=").append(result[124])
                .append(" rand_return_0x1edb64=").append(result[125])
                .append(" rand_value_count=").append(result[126])
                .append(" time_api_events=").append(result[296]);
        for (int i = 0; i < result[296] && i < 24; i++) {
            int offset = 297 + i * 4;
            output.append(" time_event_").append(i)
                    .append("=type_").append(result[offset])
                    .append("/caller_0x").append(Long.toHexString(result[offset + 1]))
                    .append(result[offset] == 1 ? "/parent_0x" : "/arg0_0x")
                    .append(Long.toHexString(result[offset + 2]))
                    .append("/arg1_0x").append(Long.toHexString(result[offset + 3]));
        }
        output.append(" time_results=").append(result[393]);
        for (int i = 0; i < result[393] && i < 24; i++) {
            int offset = 394 + i * 6;
            long fractional = result[offset + 3] | (result[offset + 4] << 32);
            output.append(" time_result_").append(i)
                    .append("=site_0x").append(Long.toHexString(result[offset]))
                    .append("/seconds_").append(result[offset + 1] | (result[offset + 2] << 32))
                    .append("/fraction_").append(fractional)
                    .append("/status_").append(result[offset + 5]);
        }
        output.append(" transform_args=")
                .append(Long.toHexString(result[544] | (result[545] << 32))).append(',')
                .append(Long.toHexString(result[546] | (result[547] << 32))).append(',')
                .append(Long.toHexString(result[548])).append(',')
                .append(Long.toHexString(result[549] | (result[550] << 32))).append(',')
                .append(Long.toHexString(result[551] | (result[552] << 32))).append(',')
                .append(Long.toHexString(result[553] | (result[554] << 32)))
                .append(" transform_return=0x")
                .append(Long.toHexString(result[584] | (result[585] << 32)));
        for (int i = 0; i < 114; i++) {
            output.append(" transform_word_").append(i).append('=')
                    .append(Long.toHexString(result[556 + i]));
        }
        output.append(" transform_call_count=").append(result[670]);
        for (int i = 0; i < 4; i++) {
            int index = 671 + (i + 4) * 2;
            long value = (result[index + 1] << 32)
                    | (result[index] & 0xffffffffL);
            output.append(" call_x").append(i + 4).append("=0x")
                    .append(Long.toHexString(value));
        }
        long x28 = (result[659] << 32) | (result[658] & 0xffffffffL);
        output.append(" call_x28=0x").append(Long.toHexString(x28))
                .append(" call_x5_read=").append(result[783])
                .append('/').append(result[785])
                .append(" call_x28_read=").append(result[784])
                .append('/').append(result[786])
                .append(" call_x5_indirect_read=").append(result[787])
                .append('/').append(result[788])
                .append(" call_x5_indirect=0x")
                .append(Long.toHexString((result[687 + 5] << 32)
                        | (result[687 + 4] & 0xffffffffL)));
        for (int i = 0; i < 32; i++) {
            output.append(" call_x5_word_").append(i).append('=')
                    .append(Long.toHexString(result[687 + i]));
        }
        for (int i = 0; i < 64; i++) {
            output.append(" call_x28_word_").append(i).append('=')
                    .append(Long.toHexString(result[719 + i]));
        }
        for (int i = 0; i < 32; i++) {
            output.append(" call_x5_indirect_word_").append(i).append('=')
                    .append(Long.toHexString(result[789 + i]));
        }
        output.append(" native_thread=").append(result[826])
                .append(" lrand48_calls=").append(result[821])
                .append(" lrand48_returns=").append(result[822])
                .append(" srand48_calls=").append(result[823])
                .append(" syscall_calls=").append(result[824])
                .append(" target_srand_calls=").append(result[899])
                .append(" direct_svc_count=").append(result[666])
                .append(" direct_svc_number=").append(result[667])
                .append(" direct_svc_result=0x")
                .append(Long.toHexString((result[669] << 32)
                        | (result[668] & 0xffffffffL)));
        for (int i = 0; i < 8; i++) {
            output.append(" lrand48_").append(i).append('=').append(result[827 + i])
                    .append("/caller_0x").append(Long.toHexString(result[835 + i]))
                    .append("/tid_").append(result[843 + i])
                    .append(" srand48_").append(i).append('=').append(result[851 + i])
                    .append("/caller_0x").append(Long.toHexString(result[859 + i]))
                    .append("/tid_").append(result[891 + i]);
            output.append(" syscall_").append(i).append('=').append(result[867 + i])
                    .append("/caller_0x").append(Long.toHexString(result[875 + i]))
                    .append("/tid_").append(result[883 + i])
                    .append(" srand_").append(i).append('=').append(result[900 + i])
                    .append("/caller_0x").append(Long.toHexString(result[908 + i]))
                    .append("/tid_").append(result[916 + i]);
        }
        output.append(" call_state_events=").append(result[924]);
        if ("baseline".equals(name)) {
            int eventCount = (int) Math.min(result[924], (long) 128);
            for (int event = 0; event < eventCount; event++) {
                int offset = 925 + event * 40;
                output.append(" x5_event_").append(event)
                        .append("=site_0x").append(Long.toHexString(result[offset]))
                        .append("/x5_0x").append(Long.toHexString(
                                result[offset + 2] << 32 | (result[offset + 1] & 0xffffffffL)))
                        .append("/x28_0x").append(Long.toHexString(
                                result[offset + 4] << 32 | (result[offset + 3] & 0xffffffffL)))
                        .append("/header_read_").append(result[offset + 5])
                        .append('/').append(result[offset + 6]);
                for (int word = 0; word < 6; word++) {
                    output.append("/h").append(word).append('_')
                            .append(Long.toHexString(result[offset + 7 + word]));
                }
                output.append("/payload_0x").append(Long.toHexString(
                        result[offset + 14] << 32
                                | (result[offset + 13] & 0xffffffffL)))
                        .append("/payload_read_").append(result[offset + 15])
                        .append('/').append(result[offset + 16]);
                for (int word = 0; word < 16; word++) {
                    output.append("/p").append(word).append('_')
                            .append(Long.toHexString(result[offset + 17 + word]));
                }
                long callerOrTarget = result[offset + 34] << 32
                        | (result[offset + 33] & 0xffffffffL);
                output.append(result[offset] == 0x16f408
                                ? "/method_target_0x" : "/caller_0x")
                        .append(Long.toHexString(callerOrTarget))
                        .append("/caller_fp_0x").append(Long.toHexString(
                                result[offset + 36] << 32
                                        | (result[offset + 35] & 0xffffffffL)))
                        .append("/parent_lr_0x").append(Long.toHexString(
                                result[offset + 38] << 32
                                        | (result[offset + 37] & 0xffffffffL)))
                        .append("/frame_read_").append(result[offset + 39]);
            }
        }
        int fileApiCountOffset = 6045;
        output.append(" file_api_events=").append(result[fileApiCountOffset]);
        for (int event = 0; event < result[fileApiCountOffset] && event < 32; event++) {
            int offset = fileApiCountOffset + 1 + event * 26;
            output.append(" file_api_").append(event)
                    .append("=type_").append(result[offset])
                    .append("/caller_0x").append(Long.toHexString(result[offset + 1]))
                    .append("/arg0_0x").append(Long.toHexString(
                            result[offset + 3] << 32 | (result[offset + 2] & 0xffffffffL)))
                    .append("/arg1_0x").append(Long.toHexString(
                            result[offset + 5] << 32 | (result[offset + 4] & 0xffffffffL)))
                    .append("/arg2_0x").append(Long.toHexString(
                            result[offset + 7] << 32 | (result[offset + 6] & 0xffffffffL)))
                    .append("/tid_").append(result[offset + 8])
                    .append("/path_bytes_").append(result[offset + 9])
                    .append("/path_hex_");
            for (int word = 0; word < 16; word++) {
                output.append(String.format(java.util.Locale.ROOT, "%08x",
                        result[offset + 10 + word] & 0xffffffffL));
            }
        }
        int allocationCountOffset = 6878;
        output.append(" allocation_events=").append(result[allocationCountOffset]);
        if ("baseline".equals(name)) {
            long x5Pointer = result[682] << 32 | (result[681] & 0xffffffffL);
            long payloadPointer = result[692] << 32 | (result[691] & 0xffffffffL);
            output.append(" transform_x5_ptr_0x").append(Long.toHexString(x5Pointer))
                    .append(" transform_payload_ptr_0x")
                    .append(Long.toHexString(payloadPointer));
            long nearestX5Distance = Long.MAX_VALUE;
            int nearestX5Offset = -1;
            long nearestPayloadDistance = Long.MAX_VALUE;
            int nearestPayloadOffset = -1;
            for (int event = 0; event < result[allocationCountOffset] && event < 2048; event++) {
                int offset = allocationCountOffset + 1 + event * 6;
                long pointer = result[offset + 3] << 32
                        | (result[offset + 2] & 0xffffffffL);
                if (pointer == x5Pointer || pointer == payloadPointer) {
                    String allocator = result[offset] == 0 ? "malloc"
                            : result[offset] == 1 ? "calloc"
                            : result[offset] == 2 ? "realloc"
                            : result[offset] == 3 ? "mmap" : "posix_memalign";
                    output.append(" allocation_match_").append(event)
                            .append('=').append(allocator)
                            .append("/size_").append(result[offset + 1])
                            .append("/ptr_0x").append(Long.toHexString(pointer))
                            .append("/caller_0x").append(Long.toHexString(result[offset + 4]))
                            .append("/tid_").append(result[offset + 5]);
                }
                long x5Distance = Math.abs(pointer - x5Pointer);
                if (x5Distance < nearestX5Distance) {
                    nearestX5Distance = x5Distance;
                    nearestX5Offset = offset;
                }
                long payloadDistance = Math.abs(pointer - payloadPointer);
                if (payloadDistance < nearestPayloadDistance) {
                    nearestPayloadDistance = payloadDistance;
                    nearestPayloadOffset = offset;
                }
            }
            if (nearestX5Offset >= 0) {
                output.append(" allocation_nearest_x5=type_")
                        .append(result[nearestX5Offset])
                        .append("/size_").append(result[nearestX5Offset + 1])
                        .append("/ptr_0x").append(Long.toHexString(
                                result[nearestX5Offset + 3] << 32
                                        | (result[nearestX5Offset + 2] & 0xffffffffL)))
                        .append("/delta_").append(nearestX5Distance)
                        .append("/caller_0x").append(Long.toHexString(result[nearestX5Offset + 4]));
            }
            if (nearestPayloadOffset >= 0) {
                output.append(" allocation_nearest_payload=type_")
                        .append(result[nearestPayloadOffset])
                        .append("/size_").append(result[nearestPayloadOffset + 1])
                        .append("/ptr_0x").append(Long.toHexString(
                                result[nearestPayloadOffset + 3] << 32
                                        | (result[nearestPayloadOffset + 2] & 0xffffffffL)))
                        .append("/delta_").append(nearestPayloadDistance)
                        .append("/caller_0x")
                        .append(Long.toHexString(result[nearestPayloadOffset + 4]));
            }
        }
        for (int i = 0; i < 9; i++) {
            output.append(" rand_value_").append(i).append('=').append(result[127 + i]);
            output.append(" rand_caller_").append(i).append("=0x")
                    .append(Long.toHexString(result[143 + i]));
        }
        output.append(" tree_node_events=")
                .append(result[TREE_NODE_EVENT_COUNT_OFFSET]);
        if ("baseline".equals(name)) {
            int eventCount = (int) Math.min(
                    result[TREE_NODE_EVENT_COUNT_OFFSET],
                    (long) TREE_NODE_EVENT_CAPACITY);
            for (int event = 0; event < eventCount; event++) {
                int offset = TREE_NODE_EVENT_COUNT_OFFSET + 1
                        + event * TREE_NODE_EVENT_WORD_COUNT;
                output.append(" tree_event_").append(event)
                        .append("=site_0x").append(Long.toHexString(result[offset]))
                        .append("/target_0x").append(Long.toHexString(
                                result[offset + 2] << 32
                                        | (result[offset + 1] & 0xffffffffL)))
                        .append("/x0_0x").append(Long.toHexString(
                                result[offset + 4] << 32
                                        | (result[offset + 3] & 0xffffffffL)))
                        .append("/x1_0x").append(Long.toHexString(
                                result[offset + 6] << 32
                                        | (result[offset + 5] & 0xffffffffL)))
                        .append("/x2_0x").append(Long.toHexString(
                                result[offset + 8] << 32
                                        | (result[offset + 7] & 0xffffffffL)))
                        .append("/x3_0x").append(Long.toHexString(
                                result[offset + 10] << 32
                                        | (result[offset + 9] & 0xffffffffL)))
                        .append("/iterator_read_").append(result[offset + 11])
                        .append('/').append(result[offset + 12])
                        .append("/node_0x").append(Long.toHexString(
                                result[offset + 14] << 32
                                        | (result[offset + 13] & 0xffffffffL)))
                        .append("/node_read_").append(result[offset + 15])
                        .append('/').append(result[offset + 16]);
                for (int word = 0; word < 16; word++) {
                    output.append("/n").append(word).append('_')
                            .append(Long.toHexString(result[offset + 17 + word]));
                }
                output.append("/value_0x").append(Long.toHexString(
                                result[offset + 34] << 32
                                        | (result[offset + 33] & 0xffffffffL)))
                        .append("/value_read_").append(result[offset + 35])
                        .append('/').append(result[offset + 36]);
                for (int word = 0; word < 14; word++) {
                    output.append("/v").append(word).append('_')
                            .append(Long.toHexString(result[offset + 37 + word]));
                }
                output.append("/return_x0_0x").append(Long.toHexString(
                                result[offset + 52] << 32
                                        | (result[offset + 51] & 0xffffffffL)))
                        .append("/return_x1_0x").append(Long.toHexString(
                                result[offset + 54] << 32
                                        | (result[offset + 53] & 0xffffffffL)))
                        .append("/return_seen_").append(result[offset + 55]);
            }
        }
        for (int i = 0; i < 8; i++) {
            output.append(" rand_object_callback_").append(i).append("=0x")
                    .append(Long.toHexString(result[159 + i]));
        }
        output.append(" rand_store_events=").append(result[167]);
        for (int i = 0; i < result[167] && i < 32; i++) {
            int offset = 168 + i * 4;
            output.append(" store_event_").append(i)
                    .append("=0x").append(Long.toHexString(result[offset]))
                    .append("/callback_0x")
                    .append(Long.toHexString(result[offset + 1]))
                    .append("/caller_0x")
                    .append(Long.toHexString(result[offset + 2]))
                    .append("/value_").append(result[offset + 3]);
        }
        output
                .append(" virtual_target_offset=0x")
                .append(Long.toHexString(result[39]))
                .append(" vtable_offset=0x")
                .append(Long.toHexString(result[40]));
        for (int i = 0; i < CORE_NESTED_CALL_SITES.length; i++) {
            long calls = result[41 + i];
            if (calls > 0) {
                output.append(" nested_0x")
                        .append(Integer.toHexString(CORE_NESTED_CALL_SITES[i]))
                        .append('=').append(calls).append("@0x")
                        .append(Long.toHexString(result[52 + i]));
            }
        }
        for (int i = 0; i < NESTED_METHOD_CALL_SITES.length; i++) {
            long calls = result[63 + i];
            if (calls > 0) {
                output.append(" method_0x")
                        .append(Integer.toHexString(NESTED_METHOD_CALL_SITES[i]))
                        .append('=').append(calls).append("@0x")
                        .append(Long.toHexString(result[92 + i]));
            }
        }
        output.append("\noutput_base64=")
                .append(signedOutput == null ? "null" : Base64.getEncoder()
                        .encodeToString(signedOutput.getBytes(StandardCharsets.UTF_8)))
                .append('\n');
    }

    private static void record(Context context, String value) {
        try (FileOutputStream output =
                     context.openFileOutput("arm64-observation.txt", Context.MODE_PRIVATE)) {
            output.write(value.getBytes(StandardCharsets.US_ASCII));
        } catch (IOException error) {
            Log.e(TAG, "Could not persist the synthetic probe status", error);
        }
    }
}