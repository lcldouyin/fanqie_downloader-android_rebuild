package com.example.fqdownloader.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class Activation {

    private static final String TAG = "Activation";
    private static final String RAW_PATH = "https://raw.githubusercontent.com/wzmwayne/fanqie_downloader-android_rebuild/main/docs";
    private static final String PUBLIC_KEY_FILE = "/public_key.pem";
    private static final String ISSUED_FILE = "/issued.json";
    private static final String ISSUED_SIG_FILE = "/issued.json.sig";
    private static final String PACKAGE_NAME = "com.example.fqdownloader";
    private static final String ACTIVE_FILE = "activation.dat";
    private static final String CACHED_KEY_FILE = "cached_public_key.pem";
    private static final String CACHED_ISSUED_FILE = "cached_issued.json";
    private static final String PREFS_NAME = "activation_prefs";
    private static final String KEY_SORTED_PROXIES = "sorted_proxies";
    private static final String KEY_TMP_TYPE = "tmp_warning_type";
    public static final String KEY_TMP_USER = "tmp_user";
    public static final String KEY_TMP_ID = "tmp_id";
    public static final String KEY_TMP_EXPIRY = "tmp_expiry";
    private static final int MAX_CONSECUTIVE_FAILURES = 5;

    private static final String[] PROXIES = {
        "",
        "https://gh.llkk.cc/",
        "https://github.tbedu.top/",
        "https://ghfile.geekertao.top/",
        "https://ghproxy.net/",
        "https://gh-proxy.com/",
        "https://gh-proxy.net/",
        "https://cdn.gh-proxy.com/",
        "https://github.dpik.top/",
        "https://j.1lin.dpdns.org/",
        "https://github.starrlzy.cn/",
        "https://github-proxy.memory-echoes.cn/",
        "https://git.yylx.win/",
        "https://ghm.078465.xyz/",
        "https://gh.927223.xyz/",
        "https://gh.felicity.ac.cn/",
        "https://gh.bugdey.us.kg/",
        "https://cdn.akaere.online/",
        "https://gh.dpik.top/",
        "https://gh.inkchills.cn/",
        "https://gh.catmak.name/",
        "https://gh.b52m.cn/",
        "https://github.mxw.qzz.io/",
        "https://gh.acmsz.top/",
        "https://gh.jjj.gv.uy/",
        "https://githubdog.com/",
        "https://gh.meali.top/",
        "https://slink.ltd/",
        "https://github.tmby.shop/",
        "https://ghpr.cc/",
        "https://gh.tryxd.cn/",
        "https://gitproxy.click/",
        "https://github.chenc.dev/",
        "https://gh.ddlc.top/",
        "https://gitproxy.mrhjx.cn/",
        "https://gh.sixyin.com/",
        "https://gh.monlor.com/",
        "https://ghfast.top/",
        "https://gh.jasonzeng.dev/",
        "https://github.geekery.cn/",
        "https://fastgit.cc/",
        "https://github.ednovas.xyz/",
        "https://ghproxy.imciel.com/",
        "https://github.xxlab.tech/",
        "https://gh.idayer.com/",
        "https://gh.chjina.com/",
        "https://ghp.keleyaa.com/",
        "https://ghproxy.monkeyray.net/",
        "https://gh.noki.icu/",
    };

    public static class AuthorizedUser {
        public String id;
        public String user;
        public String expiry;
        public int warnLevel;
        public String warnText;

        public boolean isExpired() {
            if (expiry == null || expiry.isEmpty()) return false;
            try {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                Date expiryDate = sdf.parse(expiry);
                return expiryDate != null && expiryDate.before(new Date());
            } catch (Exception e) {
                return false;
            }
        }
    }

    public interface ProgressCallback {
        void onProgress(String message);
    }

    private static class ProxyResult {
        String url;
        long timeMs;
        ProxyResult(String u, long t) { url = u; timeMs = t; }
    }

    private static class BenchmarkResult {
        List<String> sortedProxies;
        String fastestData;
    }

    public static boolean isActivated(Context ctx) {
        File f = new File(ctx.getFilesDir(), ACTIVE_FILE);
        if (!f.exists()) return false;
        AuthorizedUser u = getAuthorizedUser(ctx);
        return u != null && !u.isExpired();
    }

    public static AuthorizedUser getAuthorizedUser(Context ctx) {
        File f = new File(ctx.getFilesDir(), ACTIVE_FILE);
        if (!f.exists()) return null;
        try {
            String json = readFile(f);
            JSONObject obj = new JSONObject(json);
            AuthorizedUser u = new AuthorizedUser();
            u.id = obj.optString("id", "");
            u.user = obj.optString("user", "");
            u.expiry = obj.optString("expiry", "");
            u.warnLevel = obj.optInt("warn_level", 0);
            u.warnText = obj.optString("warn_text", "");
            return u;
        } catch (Exception e) {
            return null;
        }
    }

    public static void clearTempUserInfo(Context ctx) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_TMP_TYPE)
                .remove(KEY_TMP_USER)
                .remove(KEY_TMP_ID)
                .remove(KEY_TMP_EXPIRY)
                .apply();
    }

    public static String getTempWarningType(Context ctx) {
        return ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_TMP_TYPE, null);
    }

    public static AuthorizedUser verify(Context ctx, String activationCode, ProgressCallback cb) {
        try {
            // 激活时强制删除缓存，确保拉取最新数据
            new File(ctx.getFilesDir(), CACHED_KEY_FILE).delete();
            new File(ctx.getFilesDir(), CACHED_ISSUED_FILE).delete();
            new File(ctx.getFilesDir(), CACHED_ISSUED_FILE + ".sig").delete();

            if (activationCode == null) return null;
            activationCode = activationCode.trim();
            if (activationCode.isEmpty()) return null;

            if (cb != null) cb.onProgress("解析激活码...");

            int colon = activationCode.indexOf(':');
            if (colon < 0) return null;

            String id = activationCode.substring(0, colon);
            String sigB64 = activationCode.substring(colon + 1);
            if (id.length() != 16 || sigB64.isEmpty()) return null;

            if (cb != null) cb.onProgress("正在测速所有节点 (20线程)...");
            String testUrl = RAW_PATH + PUBLIC_KEY_FILE;
            BenchmarkResult bench = benchmarkProxies(ctx, testUrl, cb);
            if (bench == null || bench.sortedProxies.isEmpty()) {
                Log.e(TAG, "测速失败：所有节点都无法连接");
                return null;
            }
            if (cb != null) cb.onProgress("测速完成");

            PublicKey pk = fetchPublicKey(ctx, cb);
            if (pk == null) {
                Log.e(TAG, "获取公钥失败");
                return null;
            }

            if (cb != null) cb.onProgress("验证激活码签名...");
            String message = PACKAGE_NAME + "||" + id;
            byte[] sigBytes = Base64.decode(sigB64, Base64.DEFAULT);
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(pk);
            sig.update(message.getBytes(StandardCharsets.UTF_8));
            Log.i(TAG, "验证激活码签名: message=" + message + ", sigBytes=" + sigBytes.length);
            boolean sigValid = sig.verify(sigBytes);
            Log.i(TAG, "签名验证结果: " + sigValid);
            if (!sigValid) {
                Log.w(TAG, "激活码签名验证失败");
                return null;
            }

            if (cb != null) cb.onProgress("获取授权列表...");
            String issuedJsonStr = fetchIssuedJsonVerified(ctx, pk, cb);
            if (issuedJsonStr == null) {
                Log.e(TAG, "无法获取已签名的授权列表");
                return null;
            }
            Log.i(TAG, "授权列表获取成功, 长度=" + issuedJsonStr.length());

            JSONObject root = new JSONObject(issuedJsonStr);
            JSONObject codes = root.optJSONObject("codes");
            if (codes == null || !codes.has(id)) {
                Log.w(TAG, "ID 不在授权列表中");
                return null;
            }
            JSONObject entry = codes.getJSONObject(id);
            String user = entry.optString("user", "");
            String expiry = entry.optString("expiry", "");
            int warnLevel = entry.optInt("warn_level", 0);
            String warnText = entry.optString("warn_text", "");

            JSONObject saveObj = new JSONObject();
            saveObj.put("id", id);
            saveObj.put("user", user);
            saveObj.put("expiry", expiry);
            saveObj.put("warn_level", warnLevel);
            saveObj.put("warn_text", warnText);
            writeFile(new File(ctx.getFilesDir(), ACTIVE_FILE), saveObj.toString(2));

            Log.i(TAG, "激活成功, ID=" + id + ", user=" + user + ", expiry=" + expiry);

            AuthorizedUser au = new AuthorizedUser();
            au.id = id;
            au.user = user;
            au.expiry = expiry;
            au.warnLevel = warnLevel;
            au.warnText = warnText;
            return au;

        } catch (Exception e) {
            Log.e(TAG, "验证失败", e);
            return null;
        }
    }

    public static void checkAndRevoke(Context ctx) {
        checkAndRevoke(ctx, null);
    }

    public static void checkAndRevoke(Context ctx, Runnable onRevoked) {
        new Thread(() -> {
            try {
                File actFile = new File(ctx.getFilesDir(), ACTIVE_FILE);
                if (!actFile.exists()) return;

                AuthorizedUser saved = getAuthorizedUser(ctx);
                if (saved == null) return;

                PublicKey pk = fetchPublicKey(ctx, null);
                if (pk == null) {
                    Log.w(TAG, "checkAndRevoke: 无法获取公钥");
                    return;
                }

                String json = fetchIssuedJsonVerified(ctx, pk, null);
                if (json == null) {
                    Log.w(TAG, "checkAndRevoke: 无法获取授权列表");
                    return;
                }

                JSONObject root = new JSONObject(json);
                JSONObject codes = root.optJSONObject("codes");
                if (codes == null || !codes.has(saved.id)) {
                    Log.w(TAG, "checkAndRevoke: 激活码已被撤销, ID=" + saved.id);
                    saveTempUserInfo(ctx, saved, "revoked");
                    actFile.delete();
                    if (onRevoked != null) onRevoked.run();
                    return;
                }

                JSONObject entry = codes.getJSONObject(saved.id);
                saved.user = entry.optString("user", saved.user);
                saved.expiry = entry.optString("expiry", saved.expiry);
                saved.warnLevel = entry.optInt("warn_level", saved.warnLevel);
                saved.warnText = entry.optString("warn_text", saved.warnText);

                JSONObject saveObj = new JSONObject();
                saveObj.put("id", saved.id);
                saveObj.put("user", saved.user);
                saveObj.put("expiry", saved.expiry);
                saveObj.put("warn_level", saved.warnLevel);
                saveObj.put("warn_text", saved.warnText);
                writeFile(actFile, saveObj.toString(2));

                Log.i(TAG, "checkAndRevoke: 授权有效, ID=" + saved.id);

            } catch (Exception e) {
                Log.e(TAG, "checkAndRevoke 异常", e);
            }
        }).start();
    }

    public static AuthorizedUser refreshData(Context ctx, ProgressCallback cb) {
        try {
            // 强制网络拉取，不使用缓存
            new File(ctx.getFilesDir(), CACHED_KEY_FILE).delete();
            new File(ctx.getFilesDir(), CACHED_ISSUED_FILE).delete();
            new File(ctx.getFilesDir(), CACHED_ISSUED_FILE + ".sig").delete();

            if (cb != null) cb.onProgress("正在请求公钥...");
            PublicKey pk = fetchPublicKey(ctx, msg -> {
                if (cb != null) cb.onProgress("正在请求 " + msg);
            });
            if (pk == null) {
                Log.w(TAG, "数据刷新：无法获取公钥");
                return null;
            }

            if (cb != null) cb.onProgress("正在请求授权列表...");
            String json = fetchIssuedJsonVerified(ctx, pk, msg -> {
                if (cb != null) cb.onProgress("正在请求 " + msg);
            });
            if (json == null) {
                Log.w(TAG, "数据刷新：无法获取授权列表");
                return null;
            }

            File actFile = new File(ctx.getFilesDir(), ACTIVE_FILE);
            if (!actFile.exists()) return null;

            AuthorizedUser saved = getAuthorizedUser(ctx);
            if (saved == null) return null;

            JSONObject root = new JSONObject(json);
            JSONObject codes = root.optJSONObject("codes");

            if (codes == null || !codes.has(saved.id)) {
                if (cb != null) cb.onProgress("授权已撤销");
                saveTempUserInfo(ctx, saved, "revoked");
                actFile.delete();
                return null;
            }

            JSONObject entry = codes.getJSONObject(saved.id);
            saved.user = entry.optString("user", saved.user);
            saved.expiry = entry.optString("expiry", saved.expiry);
            saved.warnLevel = entry.optInt("warn_level", saved.warnLevel);
            saved.warnText = entry.optString("warn_text", saved.warnText);

            JSONObject saveObj = new JSONObject();
            saveObj.put("id", saved.id);
            saveObj.put("user", saved.user);
            saveObj.put("expiry", saved.expiry);
            saveObj.put("warn_level", saved.warnLevel);
            saveObj.put("warn_text", saved.warnText);
            writeFile(actFile, saveObj.toString(2));

            if (cb != null) cb.onProgress("更新数据完成");
            return saved;

        } catch (Exception e) {
            Log.e(TAG, "数据刷新异常", e);
            return null;
        }
    }

    // ── 测速与代理排序 ──

    private static BenchmarkResult benchmarkProxies(Context ctx, String testUrl, ProgressCallback cb) {
        int total = PROXIES.length;
        List<ProxyResult> results = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(total);
        ExecutorService executor = Executors.newFixedThreadPool(20);
        AtomicInteger completed = new AtomicInteger(0);
        AtomicLong fastestTime = new AtomicLong(Long.MAX_VALUE);
        final String[] fastestData = {null};

        Log.i(TAG, "═══════════════════════════════════════════");
        Log.i(TAG, "开始测速 " + total + " 个节点 (20线程并发)");
        Log.i(TAG, "测试地址: " + testUrl);
        Log.i(TAG, "═══════════════════════════════════════════");

        for (String proxy : PROXIES) {
            final String url = proxy + testUrl;
            final String label = proxy.isEmpty() ? "直连" : proxy;
            executor.submit(() -> {
                long elapsed = -1;
                String status = "失败";
                try {
                    long start = System.currentTimeMillis();
                    String data = httpGet(url);
                    elapsed = System.currentTimeMillis() - start;
                    if (data != null) {
                        status = elapsed + "ms";
                        results.add(new ProxyResult(proxy, elapsed));
                        long ft = fastestTime.get();
                        while (elapsed < ft) {
                            if (fastestTime.compareAndSet(ft, elapsed)) {
                                synchronized (fastestData) { fastestData[0] = data; }
                                break;
                            }
                            ft = fastestTime.get();
                        }
                    }
                } catch (Exception e) {
                    status = e.getClass().getSimpleName();
                }
                int done = completed.incrementAndGet();
                Log.i(TAG, "测速 [" + done + "/" + total + "] " + label + " → " + status);
                if (cb != null) cb.onProgress("测速 [" + done + "/" + total + "] " + label + " → " + status);
                latch.countDown();
            });
        }

        executor.shutdown();
        try { latch.await(60, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}

        Collections.sort(results, (a, b) -> Long.compare(a.timeMs, b.timeMs));

        List<String> sorted = new ArrayList<>();
        Log.i(TAG, "───────────────────────────────────────────");
        Log.i(TAG, "测速结果 (按速度排序):");
        for (int i = 0; i < results.size(); i++) {
            ProxyResult r = results.get(i);
            Log.i(TAG, "  #" + (i+1) + " " + r.url + " → " + r.timeMs + "ms");
        }
        for (ProxyResult r : results) sorted.add(r.url);
        sorted.add("");
        Log.i(TAG, "共 " + sorted.size() + " 个可用节点");
        Log.i(TAG, "最快节点: " + (results.isEmpty() ? "无" : results.get(0).url + " (" + results.get(0).timeMs + "ms)"));
        Log.i(TAG, "───────────────────────────────────────────");

        saveSortedProxies(ctx, sorted);

        if (cb != null) cb.onProgress("测速完成：共 " + sorted.size() + " 个可用节点，最快 " +
                (results.isEmpty() ? "无" : results.get(0).url + " " + results.get(0).timeMs + "ms"));

        BenchmarkResult br = new BenchmarkResult();
        br.sortedProxies = sorted;
        br.fastestData = fastestData[0];
        Log.i(TAG, "最快节点数据长度: " + (fastestData[0] == null ? "null" : fastestData[0].length()));
        return br;
    }

    private static List<String> loadSortedProxies(Context ctx) {
        String json = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_SORTED_PROXIES, null);
        if (json == null) return null;
        try {
            JSONArray arr = new JSONArray(json);
            List<String> list = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) list.add(arr.getString(i));
            return list;
        } catch (Exception e) {
            return null;
        }
    }

    private static void saveSortedProxies(Context ctx, List<String> proxies) {
        JSONArray arr = new JSONArray();
        for (String p : proxies) arr.put(p);
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SORTED_PROXIES, arr.toString())
                .apply();
    }

    // ── 网络请求 ──

    private static PublicKey fetchPublicKey(Context ctx, ProgressCallback cb) {
        File cached = new File(ctx.getFilesDir(), CACHED_KEY_FILE);
        if (cached.exists()) {
            try {
                String pem = readFile(cached);
                PublicKey pk = parsePublicKey(pem);
                if (pk != null) return pk;
            } catch (Exception e) {
                cached.delete();
            }
        }
        List<String> proxies = loadSortedProxies(ctx);
        if (proxies == null) {
            proxies = new ArrayList<>();
            for (String p : PROXIES) proxies.add(p);
        }
        String rawUrl = RAW_PATH + PUBLIC_KEY_FILE;
        for (int i = 0; i < proxies.size(); i++) {
            String proxy = proxies.get(i);
            String url = proxy + rawUrl;
            String label = proxy.isEmpty() ? "直连" : proxy;
            String msg = "获取公钥 [" + (i + 1) + "/" + proxies.size() + "] " + label;
            if (cb != null) cb.onProgress(msg);
            Log.i(TAG, msg);
            try {
                String response = httpGet(url);
                if (response != null && response.contains("BEGIN PUBLIC KEY")) {
                    try { writeFile(cached, response); } catch (Exception ignored) {}
                    PublicKey pk = parsePublicKey(response);
                    if (pk != null) {
                        Log.i(TAG, "公钥获取成功: " + url);
                        return pk;
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "失败: " + url + " → " + e.getClass().getSimpleName());
            }
        }
        return null;
    }

    private static String fetchIssuedJsonVerified(Context ctx, PublicKey pk, ProgressCallback cb) {
        File cachedFile = new File(ctx.getFilesDir(), CACHED_ISSUED_FILE);
        if (cachedFile.exists()) {
            try {
                String cached = readFile(cachedFile);
                if (cached.startsWith("{")) return cached;
            } catch (Exception e) {
                cachedFile.delete();
            }
        }

        List<String> proxies = loadSortedProxies(ctx);
        if (proxies == null) {
            proxies = new ArrayList<>();
            for (String p : PROXIES) proxies.add(p);
        }
        String jsonUrl = RAW_PATH + ISSUED_FILE;
        String sigUrl = RAW_PATH + ISSUED_SIG_FILE;
        for (int i = 0; i < proxies.size(); i++) {
            String proxy = proxies.get(i);
            String label = proxy.isEmpty() ? "直连" : proxy;
            String tag = "[" + (i + 1) + "/" + proxies.size() + "]";
            if (cb != null) cb.onProgress(tag + " " + label + " issued.json");
            try {
                byte[] jsonBytes = httpGetBytes(proxy + jsonUrl);
                if (jsonBytes == null) continue;
                String jsonStr = new String(jsonBytes, StandardCharsets.UTF_8);
                if (!jsonStr.startsWith("{")) continue;
                if (cb != null) cb.onProgress(tag + " " + label + " issued.json.sig");
                String sigResponse = httpGet(proxy + sigUrl);
                if (sigResponse == null) continue;
                String sigStr = sigResponse.trim();
                if (!sigStr.matches("^[A-Za-z0-9+/=]+$")) continue;
                if (verifyIssuedJsonSig(jsonBytes, sigStr, pk)) {
                    try { writeFile(cachedFile, jsonStr); } catch (Exception ignored) {}
                    try { writeFile(new File(ctx.getFilesDir(), CACHED_ISSUED_FILE + ".sig"), sigStr); } catch (Exception ignored) {}
                    Log.i(TAG, "授权列表获取成功: " + proxy);
                    return jsonStr;
                }
            } catch (Exception e) {
                Log.d(TAG, "失败: " + proxy + jsonUrl + " → " + e.getClass().getSimpleName());
            }
        }
        return null;
    }

    private static boolean verifyIssuedJsonSig(byte[] jsonBytes, String sigB64, PublicKey pk) {
        try {
            byte[] sigBytes = Base64.decode(sigB64, Base64.DEFAULT);
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(pk);
            sig.update(jsonBytes);
            return sig.verify(sigBytes);
        } catch (Exception e) {
            Log.e(TAG, "issued.json 签名验证异常", e);
            return false;
        }
    }

    private static PublicKey parsePublicKey(String pem) {
        try {
            String keyStr = pem
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] keyBytes = Base64.decode(keyStr, Base64.DEFAULT);
            X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return kf.generatePublic(spec);
        } catch (Exception e) {
            Log.e(TAG, "公钥解析失败", e);
            return null;
        }
    }

    private static String tryFetch(Context ctx, String rawUrl, ProgressCallback cb) {
        List<String> proxies = loadSortedProxies(ctx);
        if (proxies == null) {
            proxies = new ArrayList<>();
            for (String p : PROXIES) proxies.add(p);
        }

        int failures = 0;
        int total = proxies.size();
        for (int i = 0; i < total; i++) {
            String proxy = proxies.get(i);
            String url = proxy + rawUrl;
            String label = proxy.isEmpty() ? "直连" : proxy;
            String msg = "[" + (i + 1) + "/" + total + "] " + label;
            if (cb != null) cb.onProgress(msg);
            Log.i(TAG, msg);
            try {
                String result = httpGet(url);
                if (result != null) return result;
            } catch (Exception e) {
                Log.d(TAG, "失败: " + url + " → " + e.getClass().getSimpleName());
            }
            failures++;
            if (failures >= MAX_CONSECUTIVE_FAILURES && loadSortedProxies(ctx) != null) {
                Log.w(TAG, "连续 " + MAX_CONSECUTIVE_FAILURES + " 个节点失败，重新测速...");
                if (cb != null) cb.onProgress("正在重新测速所有节点...");
                BenchmarkResult br = benchmarkProxies(ctx, rawUrl, cb);
                if (br != null && !br.sortedProxies.isEmpty()) {
                    proxies = br.sortedProxies;
                    total = proxies.size();
                    failures = 0;
                } else {
                    break;
                }
            }
        }
        return null;
    }

    private static byte[] tryFetchBytes(Context ctx, String rawUrl, ProgressCallback cb) {
        List<String> proxies = loadSortedProxies(ctx);
        if (proxies == null) {
            proxies = new ArrayList<>();
            for (String p : PROXIES) proxies.add(p);
        }

        int failures = 0;
        int total = proxies.size();
        for (int i = 0; i < total; i++) {
            String proxy = proxies.get(i);
            String url = proxy + rawUrl;
            String label = proxy.isEmpty() ? "直连" : proxy;
            String msg = "[" + (i + 1) + "/" + total + "] " + label;
            if (cb != null) cb.onProgress(msg);
            Log.i(TAG, msg);
            try {
                byte[] result = httpGetBytes(url);
                if (result != null) return result;
            } catch (Exception e) {
                Log.d(TAG, "失败: " + url + " → " + e.getClass().getSimpleName());
            }
            failures++;
            if (failures >= MAX_CONSECUTIVE_FAILURES && loadSortedProxies(ctx) != null) {
                Log.w(TAG, "连续 " + MAX_CONSECUTIVE_FAILURES + " 个节点失败，重新测速...");
                if (cb != null) cb.onProgress("正在重新测速所有节点...");
                BenchmarkResult br = benchmarkProxies(ctx, rawUrl, cb);
                if (br != null && !br.sortedProxies.isEmpty()) {
                    proxies = br.sortedProxies;
                    total = proxies.size();
                    failures = 0;
                } else {
                    break;
                }
            }
        }
        return null;
    }

    private static String httpGet(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(6000);
        conn.setRequestProperty("User-Agent", "FqDownloader");
        int code = conn.getResponseCode();
        if (code != 200) return null;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
            return sb.toString();
        }
    }

    private static byte[] httpGetBytes(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(6000);
        conn.setReadTimeout(6000);
        conn.setRequestProperty("User-Agent", "FqDownloader");
        int code = conn.getResponseCode();
        if (code != 200) return null;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        try (InputStream is = conn.getInputStream()) {
            while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }

    public static void saveTempUserInfo(Context ctx, AuthorizedUser u, String type) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(KEY_TMP_TYPE, type)
                .putString(KEY_TMP_USER, u.user != null ? u.user : "")
                .putString(KEY_TMP_ID, u.id != null ? u.id : "")
                .putString(KEY_TMP_EXPIRY, u.expiry != null ? u.expiry : "")
                .apply();
    }

    private static String readFile(File f) throws Exception {
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] data = new byte[(int) f.length()];
            int offset = 0;
            while (offset < data.length) {
                int n = fis.read(data, offset, data.length - offset);
                if (n < 0) break;
                offset += n;
            }
            return new String(data, 0, offset, StandardCharsets.UTF_8);
        }
    }

    private static void writeFile(File f, String content) throws Exception {
        try (OutputStream os = new FileOutputStream(f)) {
            os.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}
