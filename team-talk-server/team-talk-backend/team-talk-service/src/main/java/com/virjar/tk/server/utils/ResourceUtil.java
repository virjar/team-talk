package com.virjar.tk.server.utils;

import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class ResourceUtil {
    public static List<String> readLines(String resourceName) {
        try (InputStream inputStream = openResource(resourceName)) {
            return IOUtils.readLines(inputStream, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static String readText(String resourceName) {
        try (InputStream inputStream = openResource(resourceName)) {
            return IOUtils.toString(inputStream, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static byte[] readBytes(String resourceName) {
        try (InputStream inputStream = openResource(resourceName)) {
            return IOUtils.toByteArray(inputStream);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static InputStream openResource(String name) {
        InputStream resource = ResourceUtil.class.getClassLoader()
                .getResourceAsStream(name);
        if (resource == null) {
            throw new IllegalStateException("can not find resource: " + name);
        }
        return resource;
    }
}
