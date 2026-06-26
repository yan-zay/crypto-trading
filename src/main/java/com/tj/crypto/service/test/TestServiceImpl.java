package com.tj.crypto.service.test;

import lombok.AllArgsConstructor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import java.io.IOException;

/**
 * @Author zay
 * @Date 2025/9/12 16:25
 */
@Service
@AllArgsConstructor
public class TestServiceImpl {

    private OkHttpClient okHttpClient;

    public String getApi(String url) throws IOException {
        Request request = new Request.Builder()
                .get()
                .url(url)
//                .addHeader("accept", "application/json")
//                .addHeader("CG-API-KEY", "REMOVED_SECRET")
                .build();

        // 所有服务都使用同一个okHttpClient实例，共享连接池和线程池
        try (Response response = okHttpClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                return response.body().string();
            } else {
                throw new IOException("Unexpected code: " + response);
            }
        }
    }
}
