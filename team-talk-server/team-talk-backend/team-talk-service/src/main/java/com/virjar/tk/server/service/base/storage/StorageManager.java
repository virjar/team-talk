package com.virjar.tk.server.service.base.storage;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 统一管理文件资源
 */
@Slf4j
public class StorageManager {
    private static final LocalStorage localStorage = new LocalStorage();


    public static void store(String path, File file) {
        try {
            localStorage.store(path, file);
        } catch (IOException e) {
            throw new IllegalStateException("can not save file: " + path, e);
        }
    }

    public static File get(String path) {
        File file = getImpl(path);
        if (file == null) {
            return null;
        }
        if (!file.exists()) {
            return null;
        }
        return file;
    }

    private static File getImpl(String path) {
        if (StringUtils.isBlank(path)) {
            return null;
        }
        try {
            File file = localStorage.getFile(path, null);
            if (file != null && file.exists()) {
                return file;
            }

            return file;
        } catch (IOException e) {
            throw new IllegalStateException("can not get file: " + path);
        }
    }



    public static void deleteFile(String path) {
        if (StringUtils.isBlank(path)) {
            return;
        }
        localStorage.delete(path);
    }


    public static String retrieveContent(String filePathView) {
        File file = get(filePathView);
        if (file == null || !file.exists()) {
            return "";
        }
        try {
            return FileUtils.readFileToString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("read file failed", e);
            return "";
        }
    }

    public static boolean storeWithViewPath(String filePathView, String content) {
        File resultFile = null;
        try {
            resultFile = Files.createTempFile("result", ".teamTalk").toFile();
            FileUtils.writeStringToFile(resultFile, content, StandardCharsets.UTF_8);
            store(filePathView, resultFile);
            return true;
        } catch (Exception e) {
            log.error("save result failed", e);
            return false;
        } finally {
            FileUtils.deleteQuietly(resultFile);
        }
    }

}
