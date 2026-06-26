package com.tj.crypto.config;

import com.tj.crypto.config.properties.ProxyProperties;
import lombok.AllArgsConstructor;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.logging.HttpLoggingInterceptor;
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
public class OKHttpConfig {

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
        // 设置SOCKS代理
        Proxy socksProxy = new Proxy(Proxy.Type.SOCKS,
                new InetSocketAddress(proxyProperties.getHost(), proxyProperties.getPort()));

        // 2. (可选) 创建并配置日志拦截器
        HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor();
        // 设置日志级别：NONE, BASIC, HEADERS, BODY:
        // 生产环境建议使用 BASIC 或 NONE，避免记录敏感信息
        loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BASIC);

        // 3. 构建 OkHttpClient
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .proxy(socksProxy)
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
                // 添加应用拦截器（如日志记录）
                .addInterceptor(loggingInterceptor)
                // 示例：添加一个自定义的应用拦截器（例如添加公共头）
                .addInterceptor(chain -> {
                    Request originalRequest = chain.request();
                    Request newRequest = originalRequest.newBuilder()
                            .header("User-Agent", "Your-App-Name/1.0")
                            .header("Accept", "application/json")
                            // 可以添加其他公共头
                            // .header("Authorization", "Bearer " + yourAuthToken) // 谨慎处理认证信息
                            .method(originalRequest.method(), originalRequest.body())
                            .build();
                    return chain.proceed(newRequest);
                });
        // 根据是否需要验证主机名和SSL证书，决定是否添加以下配置
        // .hostnameVerifier(customHostnameVerifier)
        // .sslSocketFactory(sslSocketFactory, trustManager)
        // 4. (重要) 构建客户端
        return builder.build();
    }
}
