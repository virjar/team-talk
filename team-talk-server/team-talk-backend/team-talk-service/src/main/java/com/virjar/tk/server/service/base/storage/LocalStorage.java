package com.virjar.tk.server.service.base.storage;

import com.virjar.tk.server.service.base.config.Settings;
import com.virjar.tk.server.service.base.storage.IStorage;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.StandardCopyOption;

@Slf4j
public class LocalStorage implements IStorage {

    @Override
    public void store(String path, File file) throws IOException {
        File targetFile = new File(Settings.Storage.localStorage, path);
        if (file.equals(targetFile)) {
            return;
        }
        FileUtils.copyFile(file, targetFile, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public void delete(String path) {
        File targetFile = new File(Settings.Storage.localStorage, path);
        if (targetFile.exists()) {
            if (!targetFile.delete()) {
                log.error("can not remove file:{}", targetFile);
            }
        }
        cleanEmptyDir(Settings.Storage.localStorage, targetFile.getParentFile());
    }

    private void cleanEmptyDir(File root, File dir) {
        if (dir.equals(root)) {
            return;
        }
        String[] list = dir.list();
        if (list == null || list.length > 0) {
            return;
        }
        FileUtils.deleteQuietly(dir);
        cleanEmptyDir(root, dir.getParentFile());
    }

    @Override
    public boolean exist(String path) {
        return new File(Settings.Storage.localStorage, path).exists();
    }

    @Override
    public File getFile(String path, File destinationFile) throws IOException {
        File file = new File(Settings.Storage.localStorage, path);
        if (destinationFile == null) {
            destinationFile = file;
        }
        if (!file.exists()) {
            return null;
        }
        if (file.equals(destinationFile)) {
            return file;
        }
        FileUtils.copyFile(file, destinationFile);
        return destinationFile;
    }

    @Override
    public String genUrl(String path) {
        // todo
        throw new IllegalStateException("not support now");
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String id() {
        return "local";
    }
}
