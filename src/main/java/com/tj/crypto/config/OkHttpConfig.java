package com.tj.crypto.config;

import com.tj.crypto.config.properties.ProxyProperties;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.concurrent.TimeUnit;

/**
 * @Author zay
 * @Date 2025/9/12 14:06
 */
@Configuration
@AllArgsConstructor
@Slf4j
public class OkHttpConfig {

    private final ProxyProperties proxyProperties;

    /**
     * 创建并配置一个 OkHttpClient 单例实例。
     * 整个应用应该共享这一个实例，以充分利用连接池和线程池。
     */
    @Bean
    public OkHttpClient createOkHttpClient() {
        // 1. 创建连接池，优化连接复用
        ConnectionPool connectionPool = new ConnectionPool(
                10,                      // 最大空闲连接数
                5,                       // 保持时间（分钟）
                TimeUnit.MINUTES         // 时间单位
        );
        // Query strings and headers are never logged: private signatures,
        // listen keys and vendor API keys can live in either location.
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                // 连接超时：建立与服务器的TCP连接所需时间
                .connectTimeout(30, TimeUnit.SECONDS)
                // 读取超时：从服务器读取数据字节之间的最长时间
                .readTimeout(30, TimeUnit.SECONDS)
                // 写入超时：向服务器发送请求时，两次连续字节写入之间的最长时间
                .writeTimeout(30, TimeUnit.SECONDS)
                // 完整调用超时：整个HTTP调用（包括重定向和重试）的总时间
                .callTimeout(120, TimeUnit.SECONDS)
                // 配置连接池
                .connectionPool(connectionPool)
                // 是否在连接失败时重试（默认为true）
                .retryOnConnectionFailure(true)
                .addInterceptor(chain -> {
                    Request request = chain.request();
                    long started = System.nanoTime();
                    String safeTarget = request.url().scheme() + "://" + request.url().host()
                            + request.url().encodedPath();
                    log.debug("HTTP --> {} {}", request.method(), safeTarget);
                    try {
                        okhttp3.Response response = chain.proceed(request);
                        log.debug("HTTP <-- {} {} {}ms", response.code(), safeTarget,
                                (System.nanoTime() - started) / 1_000_000);
                        return response;
                    } catch (java.io.IOException e) {
                        log.debug("HTTP <-- FAILED {} {}ms", safeTarget,
                                (System.nanoTime() - started) / 1_000_000);
                        throw e;
                    }
                })
                // 示例：添加一个自定义的应用拦截器（例如添加公共头）
                .addInterceptor(chain -> {
                    Request originalRequest = chain.request();
                    Request newRequest = originalRequest.newBuilder()
                            .header("User-Agent", "crypto-trading/1.0")
                            .header("Accept", "application/json")
                            // 可以添加其他公共头
                            // .header("Authorization", "Bearer " + yourAuthToken) // 谨慎处理认证信息
                            .method(originalRequest.method(), originalRequest.body())
                            .build();
                    return chain.proceed(newRequest);
                });
        if (proxyProperties.isEnabled()) {
            Proxy socksProxy = new Proxy(Proxy.Type.SOCKS,
                    new InetSocketAddress(proxyProperties.getHost(), proxyProperties.getPort()));
            builder.proxy(socksProxy);
        }
        // 根据是否需要验证主机名和SSL证书，决定是否添加以下配置
        // .hostnameVerifier(customHostnameVerifier)
        // .sslSocketFactory(sslSocketFactory, trustManager)
        // 4. (重要) 构建客户端
        return builder.build();
    }
}
