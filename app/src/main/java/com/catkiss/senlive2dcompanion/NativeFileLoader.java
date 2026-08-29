package com.catkiss.senlive2dcompanion;

import android.content.Context;

import com.live2d.sdk.cubism.framework.ICubismLoadFileFunction;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

final class NativeFileLoader implements ICubismLoadFileFunction {
    private final Context context;

    NativeFileLoader(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public byte[] load(String path) {
        try {
            File file = new File(path);
            if (file.isFile()) {
                try (InputStream input = new FileInputStream(file)) {
                    return readAll(input, file.length());
                }
            }
            try (InputStream input = context.getAssets().open(path)) {
                return readAll(input, -1L);
            }
        } catch (IOException error) {
            throw new IllegalStateException("无法读取 Cubism 文件：" + path, error);
        }
    }

    static byte[] readFile(File file) throws IOException {
        try (InputStream input = new FileInputStream(file)) {
            return readAll(input, file.length());
        }
    }

    private static byte[] readAll(InputStream input, long expectedSize) throws IOException {
        if (expectedSize >= 0L && expectedSize <= Integer.MAX_VALUE) {
            byte[] exact = new byte[(int) expectedSize];
            int offset = 0;
            while (offset < exact.length) {
                int count = input.read(exact, offset, exact.length - offset);
                if (count < 0) throw new IOException("文件读取不完整");
                offset += count;
            }
            if (input.read() == -1) return exact;
            throw new IOException("读取时文件大小发生变化");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(32 * 1024);
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        return output.toByteArray();
    }
}
