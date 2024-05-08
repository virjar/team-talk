package com.virjar.tk.server.utils;

import com.virjar.tk.server.sys.service.config.Settings;
import com.virjar.tk.server.sys.service.env.Constants;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.IOException;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Enumeration;
import java.util.Random;
import java.util.UUID;

@Slf4j
public class ServerIdentifier {

    private static final String UN_RESOLVE = "un_resolve_";

    private static String clientIdInMemory;

    public static void setupId(String id) {
        if (id == null || id.isEmpty()) {
            return;
        }
        clientIdInMemory = id;
        File file = resolveIdCacheFile();
        try {
            FileUtils.writeStringToFile(file, clientIdInMemory, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("error to write file", e);
            throw new RuntimeException(e);
        }
    }

    public static String id() {
        if (clientIdInMemory != null) {
            return clientIdInMemory;
        }
        // from cache file
        File file = resolveIdCacheFile();
        log.info("serverIdFile: " + file.getAbsolutePath());
        if (file.exists()) {
            try {
                String s = IOUtils.toString(Files.newInputStream(file.toPath()), StandardCharsets.UTF_8);
                if (s != null && !s.isEmpty() && !s.startsWith(UN_RESOLVE)) {
                    clientIdInMemory = s;
                    return clientIdInMemory;
                }
            } catch (IOException e) {
                log.error("can not read id file: " + file.getAbsolutePath(), e);
            }
        }

        clientIdInMemory = generateClientId() + "_" + new Random().nextInt(10000);
        try {
            FileUtils.writeStringToFile(file, clientIdInMemory, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("error to write file", e);
            throw new RuntimeException(e);
        }
        return clientIdInMemory;
    }


    private static String generateClientId() {
        String mac = generateClientIdForNormalJVM();
        if (StringUtils.isNotEmpty(mac)) {
            return mac;
        }
        return UN_RESOLVE + UUID.randomUUID();
    }

    private static String generateClientIdForNormalJVM() {
        try {
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface networkInterface = networkInterfaces.nextElement();
                if (networkInterface.isVirtual()) {
                    continue;
                }
                if (networkInterface.isLoopback()) {
                    continue;
                }

                byte[] hardwareAddress = networkInterface.getHardwareAddress();
                if (hardwareAddress == null) {
                    continue;
                }
                return parseByte(hardwareAddress[0]) + ":" +
                        parseByte(hardwareAddress[1]) + ":" +
                        parseByte(hardwareAddress[2]) + ":" +
                        parseByte(hardwareAddress[3]) + ":" +
                        parseByte(hardwareAddress[4]) + ":" +
                        parseByte(hardwareAddress[5]);
            }
            return null;
        } catch (SocketException e) {
            return null;
        }
    }


    private static String parseByte(byte b) {
        int intValue;
        if (b >= 0) {
            intValue = b;
        } else {
            intValue = 256 + b;
        }
        return Integer.toHexString(intValue);
    }


    private static File resolveIdCacheFile() {
        return resolveJvmEnvCacheIdFile();
    }


    private static File jvmEnvCacheIdFile = null;

    private static File resolveJvmEnvCacheIdFile() {
        if (jvmEnvCacheIdFile != null) {
            return jvmEnvCacheIdFile;
        }
        jvmEnvCacheIdFile = new File(Settings.Storage.root, Constants.serverIdFileName);
        return jvmEnvCacheIdFile;
    }

}
