package com.albus.dsh.nativeapp;

import android.app.Activity;
import android.content.Intent;
import android.hardware.Camera;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.io.IOException;

/**
 * Minimal QR scanner: camera preview + ZXing decode of the NV21 frame.
 * Returns the decoded text in the {@code qr} extra.
 */
public class ScanActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {

    private SurfaceView surfaceView;
    private Camera camera;
    private final MultiFormatReader reader = new MultiFormatReader();
    private volatile boolean done;
    private int frames;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout root = new FrameLayout(this);
        surfaceView = new SurfaceView(this);
        surfaceView.getHolder().addCallback(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(-1, -1));

        TextView hint = new TextView(this);
        hint.setText("把桌面「配对 iPhone」二维码对准取景框");
        hint.setTextColor(0xFFFFFFFF);
        hint.setTextSize(15);
        hint.setPadding(40, 60, 40, 0);
        root.addView(hint, new FrameLayout.LayoutParams(-1, -2));
        setContentView(root);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        try {
            camera = Camera.open();
            Camera.Parameters params = camera.getParameters();
            params.setPreviewSize(1280, 720);
            params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            camera.setParameters(params);
            camera.setPreviewDisplay(holder);
            camera.setPreviewCallback(this);
            camera.startPreview();
        } catch (IOException | RuntimeException error) {
            finishWith(null);
        }
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera source) {
        if (done || data == null || ++frames % 3 != 0) return;
        Camera.Size size = source.getParameters().getPreviewSize();
        try {
            PlanarYUVLuminanceSource luminance = new PlanarYUVLuminanceSource(
                    data, size.width, size.height, 0, 0, size.width, size.height, false);
            Result result = reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(luminance)));
            if (result != null && result.getText() != null) finishWith(result.getText());
        } catch (Exception ignored) {
            // No code in this frame; keep scanning.
        }
    }

    private void finishWith(String text) {
        if (done) return;
        done = true;
        if (text == null) {
            setResult(RESULT_CANCELED);
        } else {
            Intent data = new Intent();
            data.putExtra("qr", text);
            setResult(RESULT_OK, data);
        }
        finish();
    }

    @Override
    protected void onPause() {
        super.onPause();
        release();
    }

    private void release() {
        if (camera != null) {
            try {
                camera.setPreviewCallback(null);
                camera.stopPreview();
                camera.release();
            } catch (RuntimeException ignored) {
            }
            camera = null;
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        release();
    }
}
