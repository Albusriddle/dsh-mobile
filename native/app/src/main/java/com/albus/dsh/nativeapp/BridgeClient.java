package com.albus.dsh.nativeapp;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Protocol v1 client for @linxin666/dsh-remote-bridge (/ios/v1). */
public class BridgeClient {

    public interface Listener {
        void onEvent(JSONObject frame);
        void onClosed(String reason);
    }

    private final OkHttpClient http = new OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build();

    private final Map<Integer, CompletableFuture<JSONObject>> pending = new ConcurrentHashMap<>();
    private WebSocket socket;
    private Listener listener;
    private int nextId;

    /** Open the socket; a device token rides the Authorization header. */
    public void connect(String wsUrl, String deviceToken, Listener target) throws Exception {
        this.listener = target;
        Request.Builder request = new Request.Builder().url(wsUrl);
        if (deviceToken != null && !deviceToken.isEmpty()) {
            request.header("Authorization", "Bearer " + deviceToken);
        }
        CountDownLatch opened = new CountDownLatch(1);
        final Exception[] failure = new Exception[1];
        socket = http.newWebSocket(request.build(), new WebSocketListener() {
            @Override public void onOpen(WebSocket ws, Response response) {
                opened.countDown();
            }
            @Override public void onMessage(WebSocket ws, String text) {
                dispatch(text);
            }
            @Override public void onFailure(WebSocket ws, Throwable t, Response response) {
                failure[0] = new Exception(t.getMessage() == null ? t.toString() : t.getMessage());
                opened.countDown();
            }
            @Override public void onClosed(WebSocket ws, int code, String reason) {
                if (listener != null) listener.onClosed(reason);
            }
        });
        if (!opened.await(12, TimeUnit.SECONDS)) throw new Exception("连接超时");
        if (failure[0] != null) throw failure[0];
    }

    private void dispatch(String text) {
        try {
            JSONObject frame = new JSONObject(text);
            if ("res".equals(frame.optString("t"))) {
                CompletableFuture<JSONObject> future = pending.remove(frame.optInt("id"));
                if (future != null) future.complete(frame);
                return;
            }
            if (listener != null) listener.onEvent(frame);
        } catch (Exception ignored) {
            // Malformed frames are dropped rather than killing the socket.
        }
    }

    /** One request/response round trip. */
    public JSONObject call(String method, JSONObject payload, long timeoutMs) throws Exception {
        int id = ++nextId;
        JSONObject frame = new JSONObject()
                .put("t", "req").put("id", id).put("m", method).put("p", payload);
        CompletableFuture<JSONObject> future = new CompletableFuture<>();
        pending.put(id, future);
        if (socket == null) throw new Exception("未连接");
        socket.send(frame.toString());
        JSONObject response = future.get(timeoutMs, TimeUnit.MILLISECONDS);
        if (!response.optBoolean("ok", true)) {
            throw new Exception(response.optString("code", "error") + ": " + response.optString("msg", ""));
        }
        JSONObject body = response.optJSONObject("r");
        return body == null ? new JSONObject() : body;
    }

    public void close() {
        if (socket != null) socket.close(1000, "bye");
        socket = null;
    }
}
