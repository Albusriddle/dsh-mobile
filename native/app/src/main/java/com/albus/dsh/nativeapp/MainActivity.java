package com.albus.dsh.nativeapp;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * DSH native client — protocol v1.
 *
 * pair → session list (search) → open (snapshot + live deltas) → send/interrupt,
 * with approval cards answered inline and a connection status bar.
 */
public class MainActivity extends Activity {

    private static final int BG = Color.parseColor("#0f1115");
    private static final int FG = Color.parseColor("#e6e9f2");
    private static final int DIM = Color.parseColor("#9aa3bd");
    private static final int OK = Color.parseColor("#4ade80");
    private static final int WARN = Color.parseColor("#fbbf24");

    /** Declared in hello so the bridge exposes its full method surface. */
    private static final String[] CAPS = {
            "session.snapshot.trim.v1", "session.snapshot.since.v1", "delta.v1",
            "agents.v1", "commands.v1", "workspace.v1", "session.admin.v1",
            "session.fork.v1", "approvals.v1", "usage.v1", "ui.v1", "questions.v1",
    };

    private final BridgeClient bridge = new BridgeClient();
    private String base = "ws://127.0.0.1:3080";
    private String deviceToken = "";
    private String currentSession;
    private String statusText = "未连接";

    private LinearLayout root;
    private LinearLayout pairPanel;
    private LinearLayout listPanel;
    private LinearLayout chatPanel;
    private ListView listView;
    private EditText search;
    private TextView statusBar;
    private TextView transcript;
    private EditText composer;
    private ScrollView transcriptScroll;

    private final List<String> allLabels = new ArrayList<>();
    private final List<String> allIds = new ArrayList<>();
    private final List<String> listItems = new ArrayList<>();
    private final List<String> listIds = new ArrayList<>();
    private ArrayAdapter<String> listAdapter;
    private final StringBuilder chatText = new StringBuilder();
    private volatile boolean sending;
    private String pendingRequestId;
    private volatile long lastDeltaRefresh;
    private final Map<String, JSONObject> pendingApprovals = new LinkedHashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        base = prefs().getString("base", base);
        deviceToken = SecretStore.load(this);
        buildUi();
        if (deviceToken.isEmpty()) show(pairPanel); else connect(false);
    }

    private android.content.SharedPreferences prefs() {
        return getPreferences(MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        statusBar = new TextView(this);
        statusBar.setTextColor(DIM);
        statusBar.setTextSize(12);
        statusBar.setPadding(dp(14), dp(10), dp(14), dp(6));
        statusBar.setText("● " + statusText);
        root.addView(statusBar);

        pairPanel = buildPairPanel();
        listPanel = buildListPanel();
        chatPanel = buildChatPanel();
        root.addView(pairPanel, match());
        root.addView(listPanel, match());
        root.addView(chatPanel, match());
        setContentView(root);
    }

    private void setStatus(String text, int color) {
        statusText = text;
        runOnUiThread(() -> {
            statusBar.setText("● " + text);
            statusBar.setTextColor(color);
        });
    }

    private ViewGroup.LayoutParams match() {
        return new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private ViewGroup.LayoutParams matchWidth() {
        return new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView label(String text, int color, float size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(size);
        if (bold) view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setPadding(dp(16), dp(8), dp(16), dp(8));
        return view;
    }

    private LinearLayout buildPairPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(28), dp(20), dp(20));
        panel.addView(label("深海女仆工坊 · 原生版", FG, 22, true));
        panel.addView(label("粘贴桌面「配对 iPhone」面板里的地址（含 token），或只贴 token。", DIM, 13, false));

        EditText baseInput = new EditText(this);
        baseInput.setText(base);
        baseInput.setHint("ws://127.0.0.1:3080");
        baseInput.setTextColor(FG);
        panel.addView(baseInput, matchWidth());

        EditText tokenInput = new EditText(this);
        tokenInput.setHint("bootstrap token / 完整 ws 链接");
        tokenInput.setTextColor(FG);
        tokenInput.setInputType(InputType.TYPE_CLASS_TEXT);
        panel.addView(tokenInput, matchWidth());

        Button pair = new Button(this);
        pair.setText("配对");
        pair.setOnClickListener(v -> {
            String raw = tokenInput.getText().toString().trim();
            String baseValue = baseInput.getText().toString().trim();
            String token = raw;
            if (raw.startsWith("ws://") || raw.startsWith("wss://")) {
                int tokenAt = raw.indexOf("?token=");
                if (tokenAt > 0) {
                    token = raw.substring(tokenAt + 7);
                    int pathAt = raw.indexOf("/ios/v1");
                    baseValue = pathAt > 0 ? raw.substring(0, pathAt) : raw.substring(0, tokenAt);
                }
            }
            if (token.isEmpty()) { toast("请输入配对码"); return; }
            this.base = baseValue;
            prefs().edit().putString("base", baseValue).apply();
            pairAsync(token);
        });
        panel.addView(pair, matchWidth());
        panel.addView(label("提示：adb reverse tcp:3080 tcp:3080 后可连 ws://127.0.0.1:3080；外网用 wss://<ts.net 域名>", DIM, 11, false));
        return panel;
    }

    private LinearLayout buildListPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), dp(4), dp(12), 0);
        header.addView(label("会话", FG, 20, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button refresh = new Button(this);
        refresh.setText("刷新");
        refresh.setOnClickListener(v -> loadSessions());
        header.addView(refresh);
        panel.addView(header);

        search = new EditText(this);
        search.setHint("搜索会话…");
        search.setTextColor(FG);
        search.setSingleLine(true);
        search.setPadding(dp(12), 0, dp(12), 0);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { applyFilter(s.toString()); }
            @Override public void afterTextChanged(Editable s) { }
        });
        panel.addView(search, matchWidth());

        listView = new ListView(this);
        listAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, listItems) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                boolean header = listIds.get(position).isEmpty();
                view.setTextColor(header ? DIM : FG);
                view.setTextSize(header ? 12 : 16);
                view.setTypeface(header ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                return view;
            }
        };
        listView.setAdapter(listAdapter);
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            String sessionId = listIds.get(position);
            if (sessionId.isEmpty()) return false;
            showSessionMenu(sessionId);
            return true;
        });
        listView.setOnItemClickListener((parent, view, position, id) -> {
            String sessionId = listIds.get(position);
            if (!sessionId.isEmpty()) openSession(sessionId);
        });
        panel.addView(listView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return panel;
    }

    private LinearLayout buildChatPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setPadding(dp(8), dp(4), dp(8), dp(4));
        Button back = new Button(this);
        back.setText("‹ 返回");
        back.setOnClickListener(v -> { show(listPanel); loadSessions(); });
        header.addView(back);
        panel.addView(header);

        transcript = new TextView(this);
        transcript.setTextColor(FG);
        transcript.setTextSize(14);
        transcript.setTextIsSelectable(true);
        transcript.setPadding(dp(14), dp(8), dp(14), dp(8));
        transcriptScroll = new ScrollView(this);
        transcriptScroll.addView(transcript);
        panel.addView(transcriptScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(8), dp(6), dp(8), dp(10));
        composer = new EditText(this);
        composer.setHint("发消息…");
        composer.setTextColor(FG);
        composer.setMaxLines(4);
        bar.addView(composer, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button send = new Button(this);
        send.setText("发送");
        send.setOnClickListener(v -> sendPrompt());
        bar.addView(send);
        Button stop = new Button(this);
        stop.setText("停止");
        stop.setOnClickListener(v -> interrupt());
        bar.addView(stop);
        panel.addView(bar);
        return panel;
    }

    private void show(View panel) {
        pairPanel.setVisibility(panel == pairPanel ? View.VISIBLE : View.GONE);
        listPanel.setVisibility(panel == listPanel ? View.VISIBLE : View.GONE);
        chatPanel.setVisibility(panel == chatPanel ? View.VISIBLE : View.GONE);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------ protocol

    private BridgeClient.Listener listener() {
        return new BridgeClient.Listener() {
            @Override public void onEvent(JSONObject frame) { handleEvent(frame); }
            @Override public void onClosed(String reason) {
                setStatus("已断开，" + (reason == null || reason.isEmpty() ? "重连中…" : reason), WARN);
                reconnectLater();
            }
        };
    }

    /** Foreground service so the socket survives the app going to the background. */
    private void startKeepAlive() {
        android.content.Intent intent = new android.content.Intent(this, KeepAliveService.class);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void reconnectLater() {
        if (deviceToken.isEmpty()) return;
        new Thread(() -> {
            try { Thread.sleep(2000); } catch (InterruptedException ignored) { }
            connect(true);
        }).start();
    }

    private void connect(boolean silent) {
        new Thread(() -> {
            try {
                setStatus(silent ? "重连中…" : "连接中…", WARN);
                bridge.connect(base + "/ios/v1", deviceToken, listener());
                bridge.call("hello", new JSONObject()
                        .put("proto", 1).put("minor", 0).put("client", "android/0.1")
                        .put("cap", new JSONArray(CAPS)), 10000);
                setStatus("已连接 · " + base, OK);
                startKeepAlive();
                runOnUiThread(() -> { show(listPanel); loadSessions(); });
            } catch (Exception error) {
                setStatus("连接失败：" + error.getMessage(), WARN);
                if (!silent) runOnUiThread(() -> show(pairPanel));
            }
        }).start();
    }

    private void pairAsync(String token) {
        new Thread(() -> {
            try {
                setStatus("配对中…", WARN);
                bridge.connect(base + "/ios/v1", null, listener());
                JSONObject hello = bridge.call("hello", new JSONObject()
                        .put("proto", 1).put("minor", 0).put("client", "android/0.1")
                        .put("cap", new JSONArray(CAPS)), 10000);
                if (!"paired".equals(hello.optString("auth"))) {
                    JSONObject begin = bridge.call("pair.begin", new JSONObject().put("token", token), 10000);
                    byte[] rawNonce = Base64.decode(begin.getString("nonce"),
                            Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
                    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
                    generator.initialize(new ECGenParameterSpec("secp256r1"));
                    KeyPair keyPair = generator.generateKeyPair();
                    byte[] signature;
                    try {
                        Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
                        signer.initSign(keyPair.getPrivate());
                        signer.update(rawNonce);
                        signature = signer.sign();
                    } catch (Exception unsupported) {
                        Signature signer = Signature.getInstance("SHA256withECDSA");
                        signer.initSign(keyPair.getPrivate());
                        signer.update(rawNonce);
                        signature = derToRaw(signer.sign());
                    }
                    JSONObject finish = bridge.call("pair.finish", new JSONObject()
                            .put("devicePubKey", b64(keyPair.getPublic().getEncoded()))
                            .put("signature", b64(signature)), 10000);
                    deviceToken = finish.getString("deviceToken");
                    SecretStore.save(this, deviceToken);
                }
                bridge.close();
                connect(false);
            } catch (Exception error) {
                String message = error.getMessage();
                setStatus("配对失败：" + message, WARN);
                runOnUiThread(() -> toast("配对失败：" + message));
            }
        }).start();
    }

    private void loadSessions() {
        if (deviceToken.isEmpty()) return;
        new Thread(() -> {
            try {
                JSONObject result = bridge.call("session.list", new JSONObject(), 15000);
                JSONArray sessions = result.optJSONArray("sessions");
                List<String> labels = new ArrayList<>();
                List<String> ids = new ArrayList<>();
                Map<String, List<JSONObject>> byCwd = new LinkedHashMap<>();
                for (int i = 0; sessions != null && i < sessions.length(); i++) {
                    JSONObject row = sessions.getJSONObject(i);
                    byCwd.computeIfAbsent(row.optString("cwd", "(未知工作区)"), k -> new ArrayList<>()).add(row);
                }
                for (Map.Entry<String, List<JSONObject>> group : byCwd.entrySet()) {
                    labels.add(group.getKey());
                    ids.add("");
                    for (JSONObject row : group.getValue()) {
                        String title = row.optString("title", "");
                        if (title.isEmpty()) title = "(未命名)";
                        if (row.optBoolean("blank", false)) title = "＋ " + title;
                        labels.add("  " + title);
                        ids.add(row.optString("sessionId", row.optString("id", "")));
                    }
                }
                runOnUiThread(() -> {
                    allLabels.clear(); allLabels.addAll(labels);
                    allIds.clear(); allIds.addAll(ids);
                    applyFilter(search.getText().toString());
                });
            } catch (Exception error) {
                setStatus("列表失败：" + error.getMessage(), WARN);
            }
        }).start();
    }

    /** Long-press menu: rename / pin / archive through the session.admin channel. */
    private void showSessionMenu(String sessionId) {
        String[] items = {"重命名", "置顶", "归档"};
        new AlertDialog.Builder(this)
                .setTitle("会话操作")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) askRename(sessionId);
                    else if (which == 1) adminCall("session.pin", sessionId, null);
                    else adminCall("session.archive", sessionId, null);
                })
                .show();
    }

    private void askRename(String sessionId) {
        EditText input = new EditText(this);
        input.setTextColor(FG);
        new AlertDialog.Builder(this)
                .setTitle("重命名")
                .setView(input)
                .setPositiveButton("保存", (d, w) -> {
                    String title = input.getText().toString().trim();
                    if (!title.isEmpty()) adminCall("session.rename", sessionId, title);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void adminCall(String method, String sessionId, String title) {
        new Thread(() -> {
            try {
                JSONObject payload = new JSONObject().put("sessionId", sessionId);
                if (title != null) payload.put("title", title);
                bridge.call(method, payload, 15000);
                runOnUiThread(() -> { toast("已提交"); loadSessions(); });
            } catch (Exception error) {
                setStatus("操作失败：" + describe(error), WARN);
            }
        }).start();
    }

    private void applyFilter(String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        listItems.clear();
        listIds.clear();
        boolean headerPending = false;
        for (int i = 0; i < allLabels.size(); i++) {
            boolean header = allIds.get(i).isEmpty();
            String text = allLabels.get(i);
            if (header) { headerPending = false; continue; }
            if (!needle.isEmpty() && !text.toLowerCase(Locale.ROOT).contains(needle)) continue;
            if (!headerPending) {
                listItems.add(allLabels.get(previousHeader(i)));
                listIds.add("");
                headerPending = true;
            }
            listItems.add(text);
            listIds.add(allIds.get(i));
        }
        listAdapter.notifyDataSetChanged();
    }

    private int previousHeader(int index) {
        for (int i = index; i >= 0; i--) if (allIds.get(i).isEmpty()) return i;
        return 0;
    }

    private void openSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            toast("这是占位行（还没有会话 id），先新建会话再用");
            return;
        }
        currentSession = sessionId;
        chatText.setLength(0);
        new Thread(() -> {
            try {
                JSONObject result = bridge.call("session.snapshot",
                        new JSONObject().put("sessionId", sessionId), 20000);
                JSONArray turns = result.optJSONArray("turns");
                for (int i = 0; turns != null && i < turns.length(); i++) renderTurn(chatText, turns.getJSONObject(i));
                runOnUiThread(() -> {
                    transcript.setText(chatText.length() == 0 ? "(空会话)" : chatText.toString());
                    show(chatPanel);
                    scrollDown();
                });
            } catch (Exception error) {
                setStatus("打开失败：" + describe(error), WARN);
            }
        }).start();
    }

    private void scrollDown() {
        transcriptScroll.post(() -> transcriptScroll.fullScroll(View.FOCUS_DOWN));
    }

    private static String timeOf(JSONObject turn) {
        long time = turn.optLong("time", 0);
        return time == 0 ? "" : new SimpleDateFormat("MM-dd HH:mm", Locale.ROOT).format(new Date(time));
    }

    private void renderTurn(StringBuilder builder, JSONObject turn) {
        builder.append("— ").append(timeOf(turn)).append(" —\n");
        JSONArray entries = turn.optJSONArray("entries");
        for (int i = 0; entries != null && i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null) continue;
            String type = entry.optString("type");
            JSONObject data = entry.optJSONObject("data");
            if (data == null) continue;
            if ("user/message".equals(type)) {
                builder.append("\n你：").append(textOf(data.optJSONArray("content"), 2000)).append('\n');
            } else if ("assistant/message".equals(type)) {
                JSONObject message = data.optJSONObject("message");
                if (message != null) builder.append("\n助手：").append(textOf(message.optJSONArray("content"), 4000)).append('\n');
            } else if ("tool/call".equals(type)) {
                builder.append("\n⚙ ").append(data.optString("name")).append(' ')
                        .append(shorten(data.optString("arguments"), 160)).append('\n');
            } else if ("tool/result".equals(type)) {
                JSONObject message = data.optJSONObject("message");
                String text = message == null ? "" : textOf(message.optJSONArray("content"), 400);
                builder.append("  ↳ ").append(shorten(text, 400)).append('\n');
            }
        }
    }

    private String textOf(JSONArray content, int max) {
        if (content == null) return "";
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            JSONObject part = content.optJSONObject(i);
            if (part == null) continue;
            String text = part.optString("text", "");
            if (text.isEmpty()) continue;
            if ("reasoning".equals(part.optString("type"))) {
                builder.append("（思考）").append(shorten(text, 240)).append('\n');
            } else {
                builder.append(shorten(text, max)).append('\n');
            }
        }
        return builder.toString().trim();
    }

    private static String describe(Exception error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message;
    }

    private static String shorten(String text, int max) {
        if (text == null) return "";
        String single = text.replaceAll("\\s+", " ").trim();
        return single.length() <= max ? single : single.substring(0, max) + "…";
    }

    // ------------------------------------------------------------- events

    private void handleEvent(JSONObject frame) {
        String type = frame.optString("t");
        if ("approval.request".equals(type)) {
            String id = frame.optString("id");
            String summary = frame.optString("summary", "工具请求授权");
            pendingApprovals.put(id, frame);
            runOnUiThread(() -> askApproval(id, summary));
        } else if ("session.delta".equals(type)) {
            JSONObject payload = frame.optJSONObject("p");
            if (payload == null) return;
            String sessionId = payload.optString("sessionId");
            if (currentSession != null && currentSession.equals(sessionId)) {
                long now = System.currentTimeMillis();
                if (now - lastDeltaRefresh > 1200) {
                    lastDeltaRefresh = now;
                    new Thread(this::refreshTranscript).start();
                }
            }
        }
    }

    private void askApproval(String id, String summary) {
        new AlertDialog.Builder(this)
                .setTitle("需要授权")
                .setMessage(summary)
                .setPositiveButton("允许一次", (d, w) -> resolveApproval(id, "allowed-once"))
                .setNegativeButton("拒绝", (d, w) -> resolveApproval(id, "rejected"))
                .setCancelable(false)
                .show();
    }

    private void resolveApproval(String id, String decision) {
        new Thread(() -> {
            try {
                bridge.call("approval.resolve", new JSONObject().put("id", id).put("decision", decision), 10000);
                pendingApprovals.remove(id);
            } catch (Exception error) {
                setStatus("授权失败：" + error.getMessage(), WARN);
            }
        }).start();
    }

    // ------------------------------------------------------------- send

    private void sendPrompt() {
        String text = composer.getText().toString().trim();
        if (text.isEmpty() || currentSession == null) return;
        if (sending) { toast("上一条还在发送，请稍候"); return; }
        sending = true;
        if (pendingRequestId == null) pendingRequestId = UUID.randomUUID().toString();
        final String requestId = pendingRequestId;
        composer.setText("");
        chatText.append("\n你：").append(text).append('\n');
        transcript.setText(chatText.toString());
        scrollDown();
        new Thread(() -> {
            try {
                bridge.call("session.prompt", new JSONObject()
                        .put("sessionId", currentSession)
                        .put("text", text)
                        .put("requestId", requestId), 20000);
                pendingRequestId = null;
                // The bridge streams answers as deltas; a short poll keeps the
                // transcript correct even when an event is missed.
                for (int i = 0; i < 12; i++) {
                    Thread.sleep(2500);
                    refreshTranscript();
                }
            } catch (Exception error) {
                setStatus("发送失败：" + describe(error), WARN);
            } finally {
                sending = false;
            }
        }).start();
    }

    /** Re-read the whole session and repaint (cheap enough for a phone-sized turn). */
    private void refreshTranscript() {
        String sessionId = currentSession;
        if (sessionId == null) return;
        try {
            JSONObject result = bridge.call("session.snapshot",
                    new JSONObject().put("sessionId", sessionId), 20000);
            JSONArray turns = result.optJSONArray("turns");
            StringBuilder builder = new StringBuilder();
            for (int i = 0; turns != null && i < turns.length(); i++) renderTurn(builder, turns.getJSONObject(i));
            final String text = builder.length() == 0 ? "(空会话)" : builder.toString();
            if (!sessionId.equals(currentSession)) return;
            runOnUiThread(() -> {
                chatText.setLength(0);
                chatText.append(text);
                transcript.setText(text);
                scrollDown();
            });
        } catch (Exception ignored) {
            // A poll is best-effort; the next one retries.
        }
    }

    private void interrupt() {
        if (currentSession == null) return;
        new Thread(() -> {
            try { bridge.call("session.interrupt", new JSONObject().put("sessionId", currentSession), 10000); }
            catch (Exception error) { setStatus("中断失败：" + error.getMessage(), WARN); }
        }).start();
    }

    // ------------------------------------------------------------- crypto

    private static String b64(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static byte[] derToRaw(byte[] der) {
        int offset = 0;
        if (der[offset++] != 0x30) throw new IllegalArgumentException("not a DER sequence");
        int length = der[offset++] & 0xff;
        if ((length & 0x80) != 0) {
            int bytes = length & 0x7f;
            length = 0;
            for (int i = 0; i < bytes; i++) length = (length << 8) | (der[offset++] & 0xff);
        }
        byte[] out = new byte[64];
        for (int part = 0; part < 2; part++) {
            if (der[offset++] != 0x02) throw new IllegalArgumentException("not a DER integer");
            int len = der[offset++] & 0xff;
            int end = offset + len;
            int copy = Math.min(32, len);
            System.arraycopy(der, end - copy, out, part * 32 + (32 - copy), copy);
            offset = end;
        }
        return out;
    }

    /** Device tokens live in SharedPreferences, encrypted with an Android-Keystore key. */
    static final class SecretStore {
        private static final String ALIAS = "dsh-device-token";
        private static final String KEY = "deviceTokenEnc";

        static void save(Activity activity, String token) {
            try {
                SecretKey key = key();
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, key);
                byte[] iv = cipher.getIV();
                byte[] encrypted = cipher.doFinal(token.getBytes("UTF-8"));
                String packed = Base64.encodeToString(iv, Base64.NO_WRAP) + ":"
                        + Base64.encodeToString(encrypted, Base64.NO_WRAP);
                activity.getPreferences(Activity.MODE_PRIVATE).edit().putString(KEY, packed).apply();
            } catch (Exception error) {
                activity.getPreferences(Activity.MODE_PRIVATE).edit().putString(KEY, "").apply();
            }
        }

        static String load(Activity activity) {
            String packed = activity.getPreferences(Activity.MODE_PRIVATE).getString(KEY, "");
            if (packed == null || packed.isEmpty()) return "";
            try {
                String[] parts = packed.split(":");
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, key(),
                        new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
                return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), "UTF-8");
            } catch (Exception error) {
                return "";
            }
        }

        private static SecretKey key() throws Exception {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            if (keyStore.containsAlias(ALIAS)) return (SecretKey) keyStore.getKey(ALIAS, null);
            KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
            generator.init(new android.security.keystore.KeyGenParameterSpec.Builder(
                    ALIAS, android.security.keystore.KeyProperties.PURPOSE_ENCRYPT
                    | android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build());
            return generator.generateKey();
        }
    }

    @Override
    protected void onDestroy() {
        stopService(new android.content.Intent(this, KeepAliveService.class));
        bridge.close();
        super.onDestroy();
    }
}
