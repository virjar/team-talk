package com.virjar.tk.shared.client;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Set;

/** Path-backed SecureDirectoryStream test double; production never uses this fallback. */
public final class TestSecureDirectoryStream implements SecureDirectoryStream<Path> {
    private final Path directory;
    private final DirectoryStream<Path> delegate;

    public TestSecureDirectoryStream(Path directory) throws IOException {
        this.directory = directory.toAbsolutePath().normalize();
        this.delegate = Files.newDirectoryStream(this.directory);
    }

    @Override
    public Iterator<Path> iterator() {
        return delegate.iterator();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }

    @Override
    public SecureDirectoryStream<Path> newDirectoryStream(Path path, LinkOption... options) throws IOException {
        Path target = resolve(path);
        if (Arrays.asList(options).contains(LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(target)) {
            throw new FileSystemException(target.toString(), null, "symbolic link rejected by test handle");
        }
        return new TestSecureDirectoryStream(target);
    }

    @Override
    public SeekableByteChannel newByteChannel(
        Path path,
        Set<? extends OpenOption> options,
        FileAttribute<?>... attrs
    ) throws IOException {
        return Files.newByteChannel(resolve(path), options, attrs);
    }

    @Override
    public void deleteFile(Path path) throws IOException {
        Files.delete(resolve(path));
    }

    @Override
    public void deleteDirectory(Path path) throws IOException {
        Files.delete(resolve(path));
    }

    @Override
    public void move(
        Path srcpath,
        SecureDirectoryStream<Path> targetdir,
        Path targetpath
    ) throws IOException {
        if (!(targetdir instanceof TestSecureDirectoryStream)) {
            throw new IllegalArgumentException("test streams must use the same provider");
        }
        TestSecureDirectoryStream target = (TestSecureDirectoryStream) targetdir;
        Files.move(resolve(srcpath), target.resolve(targetpath), StandardCopyOption.ATOMIC_MOVE);
    }

    @Override
    public <V extends FileAttributeView> V getFileAttributeView(Class<V> type) {
        return Files.getFileAttributeView(directory, type, LinkOption.NOFOLLOW_LINKS);
    }

    @Override
    public <V extends FileAttributeView> V getFileAttributeView(
        Path path,
        Class<V> type,
        LinkOption... options
    ) {
        return Files.getFileAttributeView(resolve(path), type, options);
    }

    private Path resolve(Path relative) {
        if (relative.isAbsolute() || relative.getNameCount() != 1 ||
            relative.toString().equals(".") || relative.toString().equals("..")) {
            throw new IllegalArgumentException("secure test operation requires one relative component");
        }
        Path target = directory.resolve(relative.toString()).normalize();
        if (!target.getParent().equals(directory)) {
            throw new IllegalArgumentException("secure test operation escaped its handle");
        }
        return target;
    }
}
