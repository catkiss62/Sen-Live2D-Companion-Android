package com.catkiss.senlive2dcompanion;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.opengl.GLES20;
import android.opengl.GLUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class NativeTextureManager {
    static final class TextureInfo {
        final int id;
        final int width;
        final int height;

        TextureInfo(int id, int width, int height) {
            this.id = id;
            this.width = width;
            this.height = height;
        }
    }

    private final List<Integer> textureIds = new ArrayList<>();

    TextureInfo loadPng(File file) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        options.inPremultiplied = true;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (bitmap == null) throw new IOException("无法解码贴图：" + file.getName());

        int[] generated = new int[1];
        try {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glGenTextures(1, generated, 0);
            if (generated[0] == 0) throw new IOException("OpenGL 无法创建贴图：" + file.getName());

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, generated[0]);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);

            int error = GLES20.glGetError();
            if (error != GLES20.GL_NO_ERROR) {
                GLES20.glDeleteTextures(1, generated, 0);
                throw new IOException("OpenGL 上传贴图失败：" + file.getName() + " · 0x"
                        + Integer.toHexString(error));
            }

            textureIds.add(generated[0]);
            return new TextureInfo(generated[0], bitmap.getWidth(), bitmap.getHeight());
        } finally {
            bitmap.recycle();
        }
    }

    void releaseAll() {
        if (textureIds.isEmpty()) return;
        int[] ids = new int[textureIds.size()];
        for (int i = 0; i < ids.length; i++) ids[i] = textureIds.get(i);
        GLES20.glDeleteTextures(ids.length, ids, 0);
        textureIds.clear();
    }

    void forgetAfterContextLoss() {
        textureIds.clear();
    }
}
