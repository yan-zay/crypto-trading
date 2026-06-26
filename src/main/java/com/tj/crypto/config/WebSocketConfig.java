package com.tj.crypto.config;

import com.tj.crypto.config.properties.ProxyProperties;
import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;
import lombok.AllArgsConstructor;
import org.glassfish.tyrus.client.ClientManager;
import org.glassfish.tyrus.client.ClientProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * @Author zay
 * @Date 2025/9/15 17:17
 */
@Configuration
@AllArgsConstructor
public class WebSocketConfig {

    private ProxyProperties proxyProperties;

    @Bean
    public WebSocketContainer webSocketContainer() {
        return ContainerProvider.getWebSocketContainer();
    }

    @Bean
    public ClientManager clientManager() {
        ClientManager client = ClientManager.createClient();

        // 配置连接超时
        client.getProperties().put(ClientProperties.HANDSHAKE_TIMEOUT, //.CONNECT_TIMEOUT
                TimeUnit.SECONDS.toMillis(6));

        // 配置代理（如果需要）
        if (proxyProperties.getHost() != null && !proxyProperties.getHost().isEmpty()) {
            String proxyUri = "http://" + proxyProperties.getHost() + ":" + proxyProperties.getPort();
            client.getProperties().put(ClientProperties.PROXY_URI, proxyUri);

/*            // 如果需要代理认证
            if (proxyUsername != null && !proxyUsername.isEmpty()) {
                client.getProperties().put(ClientProperties.PROXY_USERNAME, proxyUsername);
                client.getProperties().put(ClientProperties.PROXY_PASSWORD, proxyPassword);
            }*/
        }
        return client;
    }

    @Bean
    public ClientEndpointConfig clientEndpointConfig() {
        return ClientEndpointConfig.Builder.create().build();
    }
}
