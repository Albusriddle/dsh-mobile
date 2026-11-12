package com.albus.dsh;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 深海女仆工坊 — the DSH web client as a native shell.
 *
 * The page is still served by the DSH host (same origin, so /api and the pairing
 * cookie keep working), but every static asset the host's shell references is
 * answered from the APK instead of the network. On a missing asset (host upgraded
 * since this build) the request falls through to the network.
 */
public class MainActivity extends Activity {

    private static final String DEFAULT_URL = "https://desktop-2o8tfg3.tailfc9cb6.ts.net/";
    private static final String PREFS = "dsh";
    private static final String KEY_LAST_URL = "last_url";
    private static final int REQ_FILE_CHOOSER = 1001;

    private WebView webView;
    private ProgressBar progress;
    private final Map<String, String[]> assetIndex = new HashMap<>();
    private ValueCallback<Uri[]> fileCallback;
    private int hits, misses;
    private boolean interceptEnabled = true;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WebView.setWebContentsDebuggingEnabled(true);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0F1115"));

        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor("#0F1115"));
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setTextZoom(100);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new Client());
        webView.setWebChromeClient(new Chrome());
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimetype, long contentLength) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ignored) {
                    Toast.makeText(MainActivity.this, "无法打开下载链接", Toast.LENGTH_SHORT).show();
                }
            }
        });

        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 6);
        pp.gravity = Gravity.TOP;
        root.addView(progress, pp);

        // Small always-available entry to (re)pair without leaving the app.
        TextView pair = new TextView(this);
        pair.setText("配");
        pair.setTextColor(Color.parseColor("#E8E8F0"));
        pair.setTextSize(12);
        pair.setGravity(Gravity.CENTER);
        pair.setAlpha(0.55f);
        pair.setBackgroundColor(Color.parseColor("#30344A"));
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(dp(34), dp(34));
        fp.gravity = Gravity.BOTTOM | Gravity.END;
        fp.setMargins(0, 0, dp(12), dp(16));
        root.addView(pair, fp);
        pair.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showPairDialog(); }
        });
        pair.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { showShellSettings(); return true; }
        });

        setContentView(root);
        applyOrientation();
        loadAssetIndex();

        Intent intent = getIntent();
        if (intent != null && intent.getBooleanExtra("nointercept", false)) interceptEnabled = false;
        String deepLink = intent != null && intent.getData() != null
                ? intent.getData().toString() : null;
        String start = deepLink != null ? deepLink
                : prefs().getString(KEY_LAST_URL, DEFAULT_URL);
        // First document load must not come from the cache: a stale shell can
        // carry an old remote-channel boot patch. Assets keep their own cache.
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        webView.loadUrl(start);
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Read the snapshot manifest the build's snapshot script wrote. */
    private void loadAssetIndex() {
        try (InputStream in = getAssets().open("web/asset-index.json")) {
            byte[] buf = new byte[(int) in.available()];
            //noinspection ResultOfMethodCallIgnored
            in.read(buf);
            JSONObject root = new JSONObject(new String(buf, "UTF-8"));
            JSONArray entries = root.getJSONArray("entries");
            for (int i = 0; i < entries.length(); i++) {
                JSONObject e = entries.getJSONObject(i);
                assetIndex.put(e.getString("url"),
                        new String[]{e.getString("file"), e.getString("type")});
            }
        } catch (Exception e) {
            // No snapshot: every request simply goes to the network.
        }
    }

    /** Path+query part of an absolute URL, the form the snapshot index stores. */
    private static String suffix(String url) {
        int scheme = url.indexOf("://");
        if (scheme < 0) return url;
        int slash = url.indexOf('/', scheme + 3);
        return slash < 0 ? "/" : url.substring(slash);
    }


    /**
     * The DSH client needs JS APIs newer than the device WebView ships
     * (Huawei WebView = Chromium 114: Promise.withResolvers landed in 119,
     * AbortSignal.any in 116). Without these the stream mux throws before it
     * can send its first frame, which surfaces as 「重新连接中」.
     */
    private static final String POLYFILL_JS =
        "(function(){"
      // The plugin's portrait touch layer swallows taps on session rows
      // (touchend preventDefault), so switching sessions does nothing in the
      // shell. Its documented opt-out keeps the authentic desktop layout.
      + "try{sessionStorage.setItem('dsh-remote-force-desktop','1')}catch(e){}"
      + "if(!Promise.withResolvers){Promise.withResolvers=function(){var r,j;var p=new Promise(function(a,b){r=a;j=b});return {promise:p,resolve:r,reject:j}};}"
      + "if(!AbortSignal.any){AbortSignal.any=function(list){var c=new AbortController();function on(e){c.abort(e.target.reason)}"
      + "for(var i=0;i<list.length;i++){var s=list[i];if(s.aborted){c.abort(s.reason);return c.signal}s.addEventListener('abort',on,{once:true})}return c.signal};}"
      + "if(!Object.groupBy){Object.groupBy=function(items,fn){var o=Object.create(null);var i=0;for(var v of items){var k=fn(v,i++);(o[k]||(o[k]=[])).push(v)}return o};}"
      + "if(!Map.groupBy){Map.groupBy=function(items,fn){var m=new Map();var i=0;for(var v of items){var k=fn(v,i++);if(!m.has(k))m.set(k,[]);m.get(k).push(v)}return m};}"
      + "if(typeof Set.prototype.union!=='function'){Set.prototype.union=function(o){var r=new Set(this);for(var v of o)r.add(v);return r};}"
      + "if(typeof Set.prototype.intersection!=='function'){Set.prototype.intersection=function(o){var r=new Set();for(var v of this)if(o.has(v))r.add(v);return r};}"
      + "if(typeof Set.prototype.difference!=='function'){Set.prototype.difference=function(o){var r=new Set();for(var v of this)if(!o.has(v))r.add(v);return r};}"
      + "})();";

    /** Read a bundled text asset (shell css/js). */
    private String readAssetText(String name) {
        try (java.io.InputStream in = getAssets().open(name)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** Inject the shell from assets: used when the document came from the SW cache. */
    private void injectShellFallback(WebView view) {
        if (!prefs().getBoolean("shell_enabled", true)) return;
        String css = readAssetText("mobile-shell/shell.css");
        String js = readAssetText("mobile-shell/shell.js");
        if (css != null) {
            String script = "(function(){if(document.querySelector('style[data-dsh-shell]'))return;"
                    + "var s=document.createElement('style');s.setAttribute('data-dsh-shell','1');"
                    + "s.textContent=" + org.json.JSONObject.quote(css) + ";"
                    + "(document.head||document.documentElement).appendChild(s);})()";
            view.evaluateJavascript(script, null);
        }
        if (js != null) view.evaluateJavascript(js, null);
    }

    /** Shell assets served from the APK under a synthetic path. */
    private static final String SHELL_PREFIX = "/__shell/";

    /** Polyfill + optional mobile shell chrome, injected before page scripts. */
    private String shellInjection() {
        boolean shellEnabled = prefs().getBoolean("shell_enabled", true);
        return "<script>window.__DSH_SHELL_DISABLED__=" + (!shellEnabled) + ";</script>"
                + "<link rel=\"stylesheet\" href=\"" + SHELL_PREFIX + "shell.css\">"
                + "<script src=\"" + SHELL_PREFIX + "shell.js\" defer></script>";
    }

    /** Splice the polyfill into the document before any page script runs. */
    private String injectPolyfill(String html) {
        String tag = "<script>" + POLYFILL_JS + "</script>" + shellInjection();
        int head = html.indexOf("<head");
        if (head >= 0) {
            int close = html.indexOf('>', head);
            if (close > 0) return html.substring(0, close + 1) + tag + html.substring(close + 1);
        }
        int htmlTag = html.indexOf("<html");
        if (htmlTag >= 0) {
            int close = html.indexOf('>', htmlTag);
            if (close > 0) return html.substring(0, close + 1) + tag + html.substring(close + 1);
        }
        return tag + html;
    }

    /** Fetch a document ourselves so the polyfill can be spliced in, preserving cookies. */
    private WebResourceResponse injectDocument(String url) {
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setRequestProperty("Accept-Encoding", "identity");
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null) c.setRequestProperty("Cookie", cookie);
            String ua = webView == null ? null : webView.getSettings().getUserAgentString();
            if (ua != null) c.setRequestProperty("User-Agent", ua);
            if (c.getResponseCode() != 200) return null;
            String type = c.getContentType();
            if (type == null || !type.toLowerCase().contains("text/html")) return null;
            java.io.InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            // Keep server-set cookies: the WebView no longer sees this response.
            java.util.List<String> setCookies = c.getHeaderFields().get("Set-Cookie");
            if (setCookies != null) {
                for (String value : setCookies) CookieManager.getInstance().setCookie(url, value);
            }
            String html = new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            byte[] out = injectPolyfill(html).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            headers.put("Cache-Control", "no-store");
            return new WebResourceResponse("text/html", "utf-8", 200, "OK", headers,
                    new java.io.ByteArrayInputStream(out));
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private final class Client extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            String key = suffix(request.getUrl().toString());
            if (!interceptEnabled) return null;
            String[] hit = assetIndex.get(key);
            if (hit == null) {
                misses++;
                if (misses <= 25) Log.i("DSH-ASSETS", "MISS " + key);
                return null;
            }
            hits++;
            if (hits <= 25) Log.i("DSH-ASSETS", "HIT  " + key);
            try {
                InputStream stream = getAssets().open("web/files/" + hit[0]);
                String type = hit[1];
                String charset = type != null && type.startsWith("text/") ? "utf-8" : null;
                return new WebResourceResponse(type, charset, stream);
            } catch (IOException e) {
                return null;
            }
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            view.evaluateJavascript(POLYFILL_JS, null);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            progress.setVisibility(View.GONE);
            view.getSettings().setCacheMode(WebSettings.LOAD_DEFAULT);
            injectShellFallback(view);
            String home = url;
            try {
                java.net.URI u = java.net.URI.create(url);
                if (u.getScheme() != null && u.getAuthority() != null
                        && (u.getScheme().equals("http") || u.getScheme().equals("https"))) {
                    home = u.getScheme() + "://" + u.getAuthority() + "/";
                }
            } catch (Exception ignored) { /* keep the raw url */ }
            prefs().edit().putString(KEY_LAST_URL, home).apply();
            Log.i("DSH-ASSETS", "page finished: local=" + hits + " network=" + misses + " url=" + url);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
            if (!request.isForMainFrame()) return;
            String html = "<!doctype html><meta name='viewport' content='width=device-width'>"
                    + "<body style='background:#0F1115;color:#E8E8F0;font:16px/1.6 system-ui;padding:32px'>"
                    + "<h2 style='font-weight:600'>连不上 DSH</h2>"
                    + "<p style='color:#A0A6C0'>请确认：① 手机 Tailscale 已连接；② 电脑上的 dsh web 正在运行。</p>"
                    + "<p style='color:#A0A6C0'>在家也可以点「配」-> 粘贴局域网配对链接走 192.168.43.x 直连。</p>"
                    + "<button onclick='location.reload()' style='margin-top:16px;padding:12px 20px;border-radius:12px;"
                    + "border:1px solid #4A5080;background:#232840;color:#E8E8F0'>重试</button></body>";
            view.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
        }
    }

    private final class Chrome extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            progress.setProgress(newProgress);
        }

        @Override
        public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
            Log.i("DSH-CONSOLE", m.messageLevel() + " " + m.message() + " @" + m.sourceId() + ":" + m.lineNumber());
            return true;
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = callback;
            try {
                startActivityForResult(params.createIntent(), REQ_FILE_CHOOSER);
                return true;
            } catch (Exception e) {
                fileCallback = null;
                return false;
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE_CHOOSER) {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(
                        WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                fileCallback = null;
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /** Paste a pairing link (or a LAN base URL) without leaving the app. */
    private void showPairDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), 0);

        TextView hint = new TextView(this);
        hint.setText("把桌面「远程访问」面板里的配对链接粘贴到这里（形如 …/pair-accept?pair=…）。\n"
                + "在家也可以填局域网地址：http://192.168.43.149:8080/pair-accept?pair=…");
        hint.setTextSize(13);
        box.addView(hint);

        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("https://…/pair-accept?pair=…");
        input.setSingleLine(false);
        box.addView(input);

        new AlertDialog.Builder(this)
                .setTitle("配对这台设备")
                .setView(box)
                .setPositiveButton("打开", (d, w) -> {
                    String url = input.getText().toString().trim();
                    if (url.isEmpty()) return;
                    if (!url.startsWith("http")) {
                        Toast.makeText(this, "请粘贴完整链接", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    webView.loadUrl(url);
                })
                .setNeutralButton("首页", (d, w) -> webView.loadUrl(
                        prefs().getString(KEY_LAST_URL, DEFAULT_URL)))
                .setNegativeButton("取消", null)
                .show();
    }

    /** Long-press menu on the 配 chip: shell on/off and rotation. */
    private void showShellSettings() {
        boolean shellEnabled = prefs().getBoolean("shell_enabled", true);
        boolean allowLandscape = prefs().getBoolean("allow_landscape", false);
        String[] labels = {"使用移动外壳（关闭=桌面布局）", "允许横屏"};
        boolean[] checked = {shellEnabled, allowLandscape};
        new AlertDialog.Builder(this)
                .setTitle("显示设置")
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("保存", (d, w) -> {
                    prefs().edit()
                            .putBoolean("shell_enabled", checked[0])
                            .putBoolean("allow_landscape", checked[1])
                            .apply();
                    applyOrientation();
                    webView.reload();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void applyOrientation() {
        boolean allowLandscape = prefs().getBoolean("allow_landscape", false);
        setRequestedOrientation(allowLandscape
                ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    }

    @Override
    public void onBackPressed() {
        if (webView != null) {
            webView.evaluateJavascript(
                    "(window.__DSH_SHELL__ && window.__DSH_SHELL__.isDrawerOpen && window.__DSH_SHELL__.isDrawerOpen())",
                    value -> {
                        if ("true".equals(value)) {
                            webView.evaluateJavascript("window.__DSH_SHELL__.closeDrawer()", null);
                        } else if (webView.canGoBack()) {
                            webView.goBack();
                        } else {
                            finish();
                        }
                    });
            return;
        }
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
