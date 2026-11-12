package com.albus.dsh.nativeapp;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
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
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DSH native client — protocol v1 vertical slice:
 * pair → session list → open session (snapshot) → send / interrupt.
 */
public class MainActivity extends Activity {

    private static final int BG = Color.parseColor("#0f1115");
    private static final int FG = Color.parseColor("#e6e9f2");
    private static final int DIM = Color.parseColor("#9aa3bd");
    private static final int ACCENT = Color.parseColor("#6f8cff");

    private final BridgeClient bridge = new BridgeClient();
    private String base = "ws://127.0.0.1:3080";
    private String deviceToken = "";
    private String currentSession;

    private LinearLayout root;
    private LinearLayout pairPanel;
    private LinearLayout listPanel;
    private LinearLayout chatPanel;
    private ListView listView;
    private TextView transcript;
    private EditText composer;
    private ScrollView transcriptScroll;

    private final List<String> listItems = new ArrayList<>();
    private final List<String> listIds = new ArrayList<>();
    private ArrayAdapter<String> listAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        base = getPreferences(MODE_PRIVATE).getString("base", base);
        deviceToken = getPreferences(MODE_PRIVATE).getString("deviceToken", "");
        buildUi();
        if (deviceToken.isEmpty()) {
            show(pairPanel);
        } else {
            connectAndList();
        }
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);

        pairPanel = buildPairPanel();
        listPanel = buildListPanel();
        chatPanel = buildChatPanel();

        root.addView(pairPanel, match());
        root.addView(listPanel, match());
        root.addView(chatPanel, match());
        setContentView(root);
    }

    private LinearLayout.LayoutParams match() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private LinearLayout buildPairPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(48), dp(20), dp(20));

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
                int q = raw.indexOf("?token=");
                if (q > 0) {
                    token = raw.substring(q + 7);
                    baseValue = raw.substring(0, raw.indexOf("/ios/v1") > 0 ? raw.indexOf("/ios/v1") : raw.indexOf('?'));
                }
            }
            if (token.isEmpty()) {
                toast("请输入配对码");
                return;
            }
            this.base = baseValue;
            getPreferences(MODE_PRIVATE).edit().putString("base", baseValue).apply();
            pairAsync(token);
        });
        panel.addView(pair, matchWidth());
        panel.addView(label("提示：先用 adb reverse tcp:3080 tcp:3080，或填 wss://<你的 ts.net 域名>", DIM, 11, false));
        return panel;
    }

    private LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout buildListPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(0, dp(12), 0, 0);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setPadding(dp(12), 0, dp(12), 0);
        header.addView(label("会话", FG, 20, true), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button refresh = new Button(this);
        refresh.setText("刷新");
        refresh.setOnClickListener(v -> loadSessions());
        header.addView(refresh);
        panel.addView(header);

        listView = new ListView(this);
        listAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, listItems) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(listIds.get(position).isEmpty() ? DIM : FG);
                view.setTextSize(listIds.get(position).isEmpty() ? 12 : 16);
                view.setTypeface(listIds.get(position).isEmpty() ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                return view;
            }
        };
        listView.setAdapter(listAdapter);
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
        header.setPadding(dp(12), dp(8), dp(12), dp(8));
        Button back = new Button(this);
        back.setText("‹ 返回");
        back.setOnClickListener(v -> { loadSessions(); show(listPanel); });
        header.addView(back);
        panel.addView(header);

        transcript = new TextView(this);
        transcript.setTextColor(FG);
        transcript.setTextSize(14);
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

    private void pairAsync(String token) {
        new Thread(() -> {
            try {
                bridge.connect(base + "/ios/v1", null, null);
                JSONObject hello = bridge.call("hello", new JSONObject()
                        .put("proto", 1).put("minor", 0).put("client", "android/0.1")
                        .put("cap", new JSONArray()), 10000);
                if (!"paired".equals(hello.optString("auth"))) {
                    JSONObject begin = bridge.call("pair.begin", new JSONObject().put("token", token), 10000);
                    String nonce = begin.getString("nonce");
                    KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
                    generator.initialize(new ECGenParameterSpec("secp256r1"));
                    KeyPair keyPair = generator.generateKeyPair();
                    byte[] spki = keyPair.getPublic().getEncoded();
                    byte[] rawNonce = Base64.decode(nonce, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
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
                            .put("devicePubKey", b64(spki))
                            .put("signature", b64(signature)), 10000);
                    deviceToken = finish.getString("deviceToken");
                    getPreferences(MODE_PRIVATE).edit().putString("deviceToken", deviceToken).apply();
                }
                bridge.connect(base + "/ios/v1", deviceToken, null);
                runOnUiThread(() -> { toast("配对成功"); show(listPanel); loadSessions(); });
            } catch (Exception error) {
                bridge.close();
                final String message = error.getMessage();
                runOnUiThread(() -> toast("配对失败：" + message));
            }
        }).start();
    }

    /** Reconnect with the stored device token, then show the roster. */
    private void connectAndList() {
        new Thread(() -> {
            try {
                bridge.connect(base + "/ios/v1", deviceToken, null);
                runOnUiThread(() -> { show(listPanel); loadSessions(); });
            } catch (Exception error) {
                final String message = error.getMessage();
                runOnUiThread(() -> { toast("连接失败：" + message); show(pairPanel); });
            }
        }).start();
    }

    private void loadSessions() {
        new Thread(() -> {
            try {
                if (bridge == null) return;
                JSONObject result = bridge.call("session.list", new JSONObject(), 15000);
                JSONArray sessions = result.optJSONArray("sessions");
                List<String> labels = new ArrayList<>();
                List<String> ids = new ArrayList<>();
                Map<String, List<JSONObject>> byCwd = new LinkedHashMap<>();
                for (int i = 0; sessions != null && i < sessions.length(); i++) {
                    JSONObject row = sessions.getJSONObject(i);
                    String cwd = row.optString("cwd", "(未知工作区)");
                    byCwd.computeIfAbsent(cwd, key -> new ArrayList<>()).add(row);
                }
                for (Map.Entry<String, List<JSONObject>> entry : byCwd.entrySet()) {
                    labels.add(entry.getKey());
                    ids.add("");
                    for (JSONObject row : entry.getValue()) {
                        String title = row.optString("title", "");
                        if (title.isEmpty()) title = row.optString("sessionId", "未命名");
                        labels.add("  " + title);
                        ids.add(row.optString("sessionId", row.optString("id", "")));
                    }
                }
                runOnUiThread(() -> {
                    listItems.clear(); listItems.addAll(labels);
                    listIds.clear(); listIds.addAll(ids);
                    listAdapter.notifyDataSetChanged();
                });
            } catch (Exception error) {
                final String message = error.getMessage();
                runOnUiThread(() -> toast("拉取会话失败：" + message));
            }
        }).start();
    }

    private void openSession(String sessionId) {
        currentSession = sessionId;
        new Thread(() -> {
            try {
                JSONObject result = bridge.call("session.snapshot",
                        new JSONObject().put("sessionId", sessionId), 20000);
                JSONArray turns = result.optJSONArray("turns");
                StringBuilder builder = new StringBuilder();
                for (int i = 0; turns != null && i < turns.length(); i++) {
                    renderTurn(builder, turns.getJSONObject(i));
                }
                final String text = builder.length() == 0 ? "(空会话)" : builder.toString();
                runOnUiThread(() -> {
                    transcript.setText(text);
                    show(chatPanel);
                    transcriptScroll.post(() -> transcriptScroll.fullScroll(View.FOCUS_DOWN));
                });
            } catch (Exception error) {
                final String message = error.getMessage();
                runOnUiThread(() -> toast("打开会话失败：" + message));
            }
        }).start();
    }

    private void renderTurn(StringBuilder builder, JSONObject turn) throws Exception {
        JSONArray entries = turn.optJSONArray("entries");
        for (int i = 0; entries != null && i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            String type = entry.optString("type");
            JSONObject data = entry.optJSONObject("data");
            if (data == null) continue;
            if ("user/message".equals(type)) {
                builder.append("\n你：").append(textOf(data.optJSONArray("content"))).append('\n');
            } else if ("assistant/message".equals(type)) {
                JSONObject message = data.optJSONObject("message");
                if (message != null) {
                    builder.append("\n助手：").append(textOf(message.optJSONArray("content"))).append('\n');
                }
            } else if ("tool/call".equals(type)) {
                builder.append("\n⚙ ").append(data.optString("name")).append(' ')
                        .append(shorten(data.optString("arguments"), 120)).append('\n');
            } else if ("tool/result".equals(type)) {
                JSONObject message = data.optJSONObject("message");
                String text = message == null ? "" : textOf(message.optJSONArray("content"));
                builder.append("  ↳ ").append(shorten(text, 200)).append('\n');
            }
        }
    }

    private String textOf(JSONArray content) {
        if (content == null) return "";
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < content.length(); i++) {
            JSONObject part = content.optJSONObject(i);
            if (part == null) continue;
            String type = part.optString("type");
            String text = part.optString("text", "");
            if (text.isEmpty()) continue;
            builder.append("reasoning".equals(type) ? "（思考）" + shorten(text, 200) : shorten(text, 2000));
            builder.append('\n');
        }
        return builder.toString().trim();
    }

    private static String shorten(String text, int max) {
        if (text == null) return "";
        String single = text.replaceAll("\\s+", " ").trim();
        return single.length() <= max ? single : single.substring(0, max) + "…";
    }

    private void sendPrompt() {
        String text = composer.getText().toString().trim();
        if (text.isEmpty() || currentSession == null) return;
        composer.setText("");
        new Thread(() -> {
            try {
                bridge.call("session.prompt", new JSONObject()
                        .put("sessionId", currentSession)
                        .put("text", text)
                        .put("requestId", UUID.randomUUID().toString()), 20000);
                runOnUiThread(() -> toast("已发送"));
            } catch (Exception error) {
                final String message = error.getMessage();
                runOnUiThread(() -> toast("发送失败：" + message));
            }
        }).start();
    }

    private void interrupt() {
        if (currentSession == null) return;
        new Thread(() -> {
            try {
                bridge.call("session.interrupt", new JSONObject().put("sessionId", currentSession), 10000);
                runOnUiThread(() -> toast("已中断"));
            } catch (Exception error) {
                final String message = error.getMessage();
                runOnUiThread(() -> toast("中断失败：" + message));
            }
        }).start();
    }

    private static String b64(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    /** DER ECDSA signature → 64-byte raw r||s (some Android versions lack P1363). */
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
            int start = offset;
            int end = offset + len;
            int copy = Math.min(32, len);
            int dest = part * 32 + (32 - copy);
            System.arraycopy(der, end - copy, out, dest, copy);
            offset = end;
        }
        return out;
    }

    @Override
    protected void onDestroy() {
        bridge.close();
        super.onDestroy();
    }
}
