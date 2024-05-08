package com.virjar.tk.server.framework.net;

import com.virjar.tk.server.sys.service.config.Configs;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.ResourceUtils;

import javax.net.ssl.KeyManagerFactory;
import java.security.KeyStore;

@Slf4j
public class KeyStoreLoader {
    private static final String keyStore = Configs.getConfig("ssl.keystore.file");

    private static final String keyStorePassword = Configs.getConfig("ssl.keystore.password");

    private static final String keyStoreType = Configs.getConfig("ssl.keystore.type");

    @Getter
    private static SslContext customSslContext;

    static {
        load();
    }



    private static void load() {
        try {
            if (StringUtils.isAnyBlank(keyStore, keyStoreType)) {
                log.info("the server not configure custom keystore");
                return;
            }
            log.info("begin load ssl keystore");
            char[] password = StringUtils.isBlank(keyStorePassword) ? null : keyStorePassword.toCharArray();
            KeyStore ks = KeyStore.getInstance(keyStoreType);
            ks.load(ResourceUtils.getURL(keyStore).openStream(), password);
            KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance("SunX509");
            keyManagerFactory.init(ks, password);
            customSslContext = SslContextBuilder.forServer(keyManagerFactory).build();
        } catch (Exception e) {
            log.error("load custom key store failed", e);
        }
    }
}
