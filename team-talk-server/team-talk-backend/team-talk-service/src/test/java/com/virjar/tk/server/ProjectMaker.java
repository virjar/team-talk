package com.virjar.tk.server;

import com.virjar.tk.server.utils.ResourceUtil;
import lombok.Getter;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 此工具，用于对项目符号进行批量改名。改名后生成一个全新的项目，
 */
public class ProjectMaker {
    public static void main(String[] args) throws Throwable {
        // 此代码在idea软件中执行
        List<String> configRules = ResourceUtil.readLines("make_project.conf.txt");
        // 指向java后端根目录：team-talk-backend
        String backendProjectRoot = System.getProperty("user.dir");

        File myProjectRoot = new File(backendProjectRoot).getParentFile();
        Maker.doMake(configRules, myProjectRoot);
    }

    static class Maker {
        private static GenRule genRule;
        public static String rootDirStr;

        public static void doMake(List<String> configRules, File srcProjectRoot) throws Throwable {
            // 构建规则
            genRule = GenRule.parse(configRules);
            // 扫描输出目录的历史文件，我们需要计算需要被删除的文件的diff
            // diff定义则为，源不存在、目标存在；且不为忽略文件
            genRule.scanOutputOldDir();

            rootDirStr = srcProjectRoot.getAbsoluteFile().getCanonicalPath();
            File srcRootDir = new File(rootDirStr);
            genRule.init(srcRootDir);
            for (File ignoreFile : genRule.ignoreFiles.runtimeMatchFiles) {
                String relativePath = ignoreFile.getCanonicalPath().substring(rootDirStr.length() + 1);
                genRule.root.remove(relativePath);
            }

            Files.walkFileTree(srcRootDir.toPath(), new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    return handleFile(file, attrs);
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    return handleFile(dir, attrs);
                }
            });

            // 同步需要被删除的文件
            genRule.root.doClean();

            for (String cmd : genRule.cmds) {
                String rewriteCmd = contentReplace(cmd);
                Shell.execute(rewriteCmd, null, genRule.outputRootDir);
            }
        }


        private static FileVisitResult handleFile(Path path, BasicFileAttributes attrs) throws IOException {
            File file = path.toFile();
            //System.out.println("handle file: " + file.getAbsolutePath());

            // 当前文件的相对路径
            String relativePath = file.getCanonicalPath().substring(rootDirStr.length());
            boolean needIgnore = genRule.ignoreFiles.match(file);
            // 文件忽略逻辑
            if (needIgnore) {
                genRule.root.remove(relativePath);
                return attrs.isDirectory() ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            // 实际上只处理文件
            if (attrs.isDirectory()) {
                // 文件夹的话，需要来计算keep规则
                genRule.keepFiles.match(file);
                genRule.notOverwrite.match(file);
                return FileVisitResult.CONTINUE;
            }

            genRule.root.remove(relativePath);

            if (genRule.keepFiles.match(file)) {
                // 如果是keep文件，那么直接复制过去，这个行为主要为了应对二进制文件，避免对二进制执行replace操作
                // 因为我们会把所有打算修改的文件使用文本方式载入到内存
                File target = new File(genRule.outputRootDir, contentReplace(relativePath));
                FileUtils.copyFile(file, target);
                return FileVisitResult.CONTINUE;
            }

            if (genRule.notOverwrite.match(file)) {
                File target = new File(genRule.outputRootDir, contentReplace(relativePath));
                if (target.exists()) {
                    // 如果存在，则不能覆盖
                    return FileVisitResult.CONTINUE;
                }
            }


            // 输出文件路径
            File outFile = calcOutFile(FileUtils.readLines(file, StandardCharsets.UTF_8), relativePath);
            String newRelativePath = outFile.getCanonicalPath().substring(genRule.outputRootDir.getCanonicalPath().length() + 1);
            genRule.root.remove(newRelativePath);

            FileUtils.forceMkdirParent(outFile);

            // 执行内容的replace规则
            String content = contentReplace(FileUtils.readFileToString(file, StandardCharsets.UTF_8));
            FileUtils.write(outFile, content, StandardCharsets.UTF_8);
            System.out.println("write file: " + outFile.getAbsolutePath());
            return FileVisitResult.CONTINUE;
        }

        private static final Pattern[] patterns = new Pattern[]{
                Pattern.compile("package\\s+(\\S+).*?;"),
                Pattern.compile("package\\s+(\\S+)")
        };
        // java: 普通java文件
        // aidl：Android上的跨进程通行协议定义文件
        // groovy：java变种，我们比较多的使用groovy描述动态规则
        // kts：kotlin，java变种，在Android项目中比较容易遇到
        private static final String[] javaLikeFiles = new String[]{".java", ".aidl", ".groovy", ".kts"};

        private static File calcOutFile(List<String> lines, String relativePath) {
            // 对于java和aidl文件，我们需要修改包名，此时需要重新计算文件路径
            // 对于其他类型文件，我们只会考虑修改文件内容，不会修改文件路径规则
            boolean isJavaLikeFile = false;
            for (String str : javaLikeFiles) {
                if (relativePath.endsWith(str)) {
                    isJavaLikeFile = true;
                    break;
                }
            }
            if (!isJavaLikeFile) {
                return new File(genRule.outputRootDir, contentReplace(relativePath));
            }

            String[] contents = new String[]{removeJavaComment(lines), StringUtils.join(lines, "\n")};

            Matcher matcher = null;
            for (String content : contents) {
                for (Pattern pattern : patterns) {
                    Matcher tmpMatcher = pattern.matcher(content);
                    if (tmpMatcher.find()) {
                        matcher = tmpMatcher;
                        break;
                    }
                }
                if (matcher != null) {
                    break;
                }
            }

            if (matcher == null) {
                System.out.println("failed to handle java like file: " + relativePath);
                return new File(genRule.outputRootDir, contentReplace(relativePath));
            }


            // 通过正则规则抽取了
            String pkg = matcher.group(1);

            String nowRule = null;
            for (String str : genRule.pkgRenameRules.keySet()) {
                if (!pkg.startsWith(str)) {
                    continue;
                }
                if (nowRule == null) {
                    nowRule = str;
                } else if (str.length() > nowRule.length()) {
                    // 长路径规则优先
                    nowRule = str;
                }
            }
            String targetRelatePath = nowRule == null ? relativePath : relativePath.replace(
                    nowRule.replace(".", "/"),
                    genRule.pkgRenameRules.get(nowRule).replace(".", "/")
            );
            // 文件名称也需要修改
            return new File(genRule.outputRootDir, contentReplace(targetRelatePath));
        }


        private static String contentReplace(String input) {
            for (Map.Entry<String, String> keep : genRule.keepContents.entrySet()) {
                input = input.replace(keep.getKey(), keep.getValue());
            }
            // 文件名称也需要修改
            for (Map.Entry<GenRule.KeywordString, String> keywordReplaceRule : genRule.keywordReplaceRules.entrySet()) {
                input = input.replace(keywordReplaceRule.getKey().getStr(), keywordReplaceRule.getValue());
            }
            for (Map.Entry<String, String> keep : genRule.keepContents.entrySet()) {
                input = input.replace(keep.getValue(), keep.getKey());
            }
            return input;
        }


        private static String removeJavaComment(List<String> content) {
            StringBuilder sb = new StringBuilder();
            int state = 0;// 0:begin, 1:multi line comment
            String tempStr = null;
            for (int i = 0; i < content.size(); i++) {
                String handleStr;
                if (tempStr != null) {
                    handleStr = tempStr;
                    tempStr = null;
                } else {
                    handleStr = content.get(i);
                }
                switch (state) {
                    case 0:
                        handleStr = handleStr.trim();
                        if (handleStr.startsWith("//")) {
                            continue;
                        }
                        if (handleStr.startsWith("/*")) {
                            int index = handleStr.indexOf("*/");
                            if (index >= 2) {
                                handleStr = handleStr.substring(index + 2);
                                if (StringUtils.isNotBlank(handleStr)) {
                                    tempStr = handleStr;
                                    i--;
                                }
                                continue;
                            }
                            state = 1;
                        } else {
                            sb.append(handleStr);
                            sb.append("\n");
                        }
                        continue;
                    case 1:
                        int index = handleStr.indexOf("*/");
                        if (index >= 0) {
                            handleStr = handleStr.substring(index + 2);
                            if (StringUtils.isNotBlank(handleStr)) {
                                tempStr = handleStr;
                                i--;
                            }
                            state = 0;
                        }

                }
            }
            return sb.toString();
        }
    }


    static class Shell {
        public static void execute(String cmd, String[] envp, File dir) throws IOException, InterruptedException {
            System.out.println("execute cmd: " + cmd);
            Process process = Runtime.getRuntime().exec(cmd, envp, dir);
            new InputStreamPrintThread(process.getErrorStream());
            new InputStreamPrintThread(process.getInputStream());
            process.waitFor();
        }

        public static class InputStreamPrintThread extends Thread {
            private final InputStream inputStream;

            public InputStreamPrintThread(InputStream inputStream) {
                this.inputStream = inputStream;
                start();
            }

            @Override
            public void run() {
                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
                String line;
                try {
                    while ((line = bufferedReader.readLine()) != null) {
                        System.out.println(line);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    static class MultiConfigRule {
        private final Map<String, List<String>> config = new HashMap<>();

        private MultiConfigRule() {
        }

        public interface ConfigItemHandler {
            void handle(String item);
        }

        public MultiConfigRule accessValue(String key, ConfigItemHandler handler) {
            for (String value : getList(key)) {
                handler.handle(value);
            }
            return this;
        }

        public List<String> getList(String key) {
            List<String> strings = config.get(key.toUpperCase());
            if (strings == null) {
                return Collections.emptyList();
            }
            return strings;
        }

        public static MultiConfigRule parse(List<String> configRules) {
            MultiConfigRule multiConfigRule = new MultiConfigRule();
            for (String line : configRules) {
                line = line.trim();
                if (StringUtils.isBlank(line) || line.startsWith("#")) {
                    // comment
                    continue;
                }
                multiConfigRule.parseLine(line);
            }
            return multiConfigRule;
        }

        private void parseLine(String line) {
            int index = line.indexOf(":");
            if (index <= 0) {
                System.out.println("error config:" + line);
                return;
            }

            String cmd = line.substring(0, index).trim().toUpperCase();
            String content = line.substring(index + 1).trim();
            config.computeIfAbsent(cmd, k -> new ArrayList<>()).add(content);
        }
    }

    static class FileMatcher {
        private final Set<String> rawRules = new HashSet<>();

        public final Set<File> runtimeMatchFiles = new HashSet<>();
        private final Set<File> runtimeMatchDirs = new HashSet<>();

        public void addRule(String rule) {
            rawRules.add(rule.trim());
        }

        public void resolveRootDir(File root) {
            // 所有绝对路径的的忽略规则
            Iterator<String> iterator = rawRules.iterator();
            while (iterator.hasNext()) {
                String ignoreConfig = iterator.next();
                if (ignoreConfig.startsWith("/")) {
                    runtimeMatchFiles.add(new File(root, ignoreConfig.substring(1)).getAbsoluteFile());
                    iterator.remove();
                }
            }
        }

        private boolean matchWithoutRuntimeRule(File file) {
            if (runtimeMatchFiles.contains(file)) {
                return true;
            }
            for (File dir : runtimeMatchDirs) {
                // 判断是否是目标文件夹中的文件的子目录
                String basePath = dir.getAbsolutePath();
                if (file.getAbsolutePath().equals(basePath)) {
                    return true;
                }
                if (file.getAbsolutePath().startsWith(basePath + "/")) {
                    return true;
                }
            }
            return false;
        }

        public boolean match(File file) {
            if (matchWithoutRuntimeRule(file)) {
                return true;
            }
            boolean hasWildcardMatch = false;
            for (String config : rawRules) {
                File newIgnoreFile = file.toPath().resolve(config).toFile();
                if (newIgnoreFile.exists() || config.equals(file.getName())) {
                    if (newIgnoreFile.isDirectory()) {
                        runtimeMatchDirs.add(newIgnoreFile);
                    } else {
                        runtimeMatchFiles.add(newIgnoreFile);
                    }
                    continue;
                }

                if (config.startsWith("*.")) {
                    String suffix = config.substring(2);
                    if (file.getName().endsWith(suffix)) {
                        runtimeMatchFiles.add(file);
                        hasWildcardMatch = true;
                    }
                }
            }
            if (hasWildcardMatch) {
                return true;
            }
            return matchWithoutRuntimeRule(file);
        }

    }

    static class GenRule {
        public Map<String, String> pkgRenameRules = new HashMap<>();
        public Map<KeywordString, String> keywordReplaceRules = new TreeMap<>();

        public static class KeywordString implements Comparable<KeywordString> {
            private static final AtomicInteger inc = new AtomicInteger(0);
            private static final Map<String, KeywordString> registry = new ConcurrentHashMap<>();

            public static KeywordString valueOf(String key) {
                KeywordString keywordString = registry.get(key);
                if (keywordString != null) {
                    return keywordString;
                }
                synchronized (KeywordString.class) {
                    keywordString = registry.get(key);
                    if (keywordString != null) {
                        return keywordString;
                    }
                    keywordString = new KeywordString(key);
                    registry.put(key, keywordString);
                }
                return keywordString;
            }

            @Getter
            private final String str;
            private final Integer seq;

            public KeywordString(String str) {
                this.str = str;
                seq = inc.incrementAndGet();
            }

            @Override
            public int compareTo(KeywordString o) {
                int priority1 = specialPriority(str);
                int priority2 = specialPriority(o.str);
                if (priority1 != priority2) {
                    return priority2 - priority1;
                }
                return seq.compareTo(o.seq);
            }

            private static int specialPriority(String str) {
                // java文件路径：src/main/java/com/virjar/tk/server/service/base/env/Constants.java
                if (str.contains("/")) {
                    return str.split("/").length + 500;
                }
                // 包名需要高优先级替换
                if (str.contains(".")) {
                    return str.split("\\.").length + 1000;
                }
                // jni声明也属于包名
                if (str.startsWith("Java_")) {
                    return str.split("_").length + 2000;
                }
                return 0;
            }

            @Override
            public String toString() {
                return str + ":" + seq;
            }
        }


        public FileMatcher ignoreFiles = new FileMatcher();
        public FileMatcher keepFiles = new FileMatcher();
        public FileMatcher notOverwrite = new FileMatcher();
        public Map<String, String> keepContents = new HashMap<>();
        public List<String> cmds = new ArrayList<>();

        public FSNode root = new FSNode("root", true, null);
        public File outputRootDir;

        public static GenRule parse(List<String> configRules) {
            GenRule genRule = new GenRule();
            MultiConfigRule multiConfigRule = MultiConfigRule.parse(configRules);
            multiConfigRule
                    .accessValue("PKG", item -> {
                        String[] pkgRule = item.split("->");
                        genRule.pkgRenameRules.put(pkgRule[0].trim(), pkgRule[1].trim());
                    }).accessValue("KEY", item -> {
                        String[] keyRule = item.split("->");
                        genRule.keywordReplaceRules.put(KeywordString.valueOf(keyRule[0].trim()), keyRule[1].trim());
                    })
                    .accessValue("TARGET", item -> genRule.outputRootDir = resolveFile(item.trim()))
                    .accessValue("IGNORE", item -> genRule.ignoreFiles.addRule(item.trim()))
                    .accessValue("KEEP", item -> genRule.keepFiles.addRule(item.trim()))
                    .accessValue("NOTOVERWRITE", item -> genRule.notOverwrite.addRule(item.trim()))
                    .accessValue("KEEPCONTENT", item -> genRule.keepContents.put(item.trim(), UUID.randomUUID().toString()))
                    .accessValue("CMD", item -> genRule.cmds.add(item));
            return genRule;
        }

        public static File resolveFile(String path) {
            path = path.trim();
            if (path.startsWith("~/")) {
                path = FileUtils.getUserDirectoryPath() +
                        path.substring(1);
            }
            return new File(path);
        }

        public void init(File root) {
            ignoreFiles.resolveRootDir(root);
            keepFiles.resolveRootDir(root);
            notOverwrite.resolveRootDir(root);


            // java的替换规则，需要产生对应的keyword规则
            for (Map.Entry<String, String> pkgRule : pkgRenameRules.entrySet()) {
                String from = pkgRule.getKey();
                String to = pkgRule.getValue();

                keywordReplaceRules.put(KeywordString.valueOf(from), to);
                keywordReplaceRules.put(KeywordString.valueOf(pkg2JniName(from)), pkg2JniName(to));
                keywordReplaceRules.put(KeywordString.valueOf(pkg2PathName(from)), pkg2PathName(to));
            }
        }

        private static String pkg2PathName(String pkg) {
            return pkg.replace('.', '/');
        }

        private static String pkg2JniName(String pkg) {
            return "Java_" + pkg.replaceAll("\\.", "_");
        }

        public void scanOutputOldDir() throws IOException {
            if (!outputRootDir.exists()) {
                FileUtils.forceMkdir(outputRootDir);
                return;
            }
            // 所有已知文件，把他搞到内存中
            String rootDirStr = outputRootDir.getCanonicalPath();
            Files.walkFileTree(outputRootDir.toPath(), new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    String relativePath = file.toFile().getCanonicalPath().substring(rootDirStr.length() + 1);
                    root.addChild(relativePath, false, file.toFile());
                    return FileVisitResult.CONTINUE;
                }

            });

        }


        public static class FSNode {
            private final String name;
            private final boolean isDir;
            private final File file;
            private Map<String, FSNode> children;

            public FSNode(String name, boolean isDir, File file) {
                this.name = name;
                this.isDir = isDir;
                this.file = file;
                if (isDir) {
                    children = new LinkedHashMap<>();
                }
            }

            public void addChild(String path, boolean isDir, File file) {
                if (!this.isDir) {
                    throw new IllegalStateException("can not add new ");
                }
                if (path.endsWith("/")) {
                    path = path.substring(0, path.length() - 1);
                    isDir = true;
                }
                int index = path.indexOf("/");
                if (index < 0) {
                    children.put(path, new FSNode(path, isDir, file));
                } else {
                    String dir = path.substring(0, index);
                    String left = path.substring(index + 1);

                    FSNode child = children.get(dir);
                    if (child == null) {
                        child = new FSNode(dir, true, null);
                        children.put(dir, child);
                    }
                    child.addChild(left, isDir, file);
                }
            }

            public void doClean() {
                if (!isDir && file != null) {
                    System.out.println("remove file: " + file.getAbsolutePath());
                    FileUtils.deleteQuietly(file);
                }
                if (isDir) {
                    for (FSNode child : children.values()) {
                        child.doClean();
                    }
                    if (file != null) {
                        System.out.println("remove dir: " + file.getAbsolutePath());
                    }
                    FileUtils.deleteQuietly(file);
                }
            }


            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                FSNode fsNode = (FSNode) o;
                return Objects.equals(name, fsNode.name);
            }

            @Override
            public int hashCode() {
                return Objects.hash(name);
            }

            public void remove(String path) {
                if (path.startsWith("/")) {
                    path = path.substring(1);
                }
                if (path.endsWith("/")) {
                    path = path.substring(0, path.length() - 1);
                }
                int index = path.indexOf("/");
                if (index < 0) {
                    children.remove(path);
                    return;
                }
                String dir = path.substring(0, index);
                String left = path.substring(index + 1);

                FSNode child = children.get(dir);
                if (child == null) {
                    return;
                }
                child.remove(left);
                if (child.isDir && child.children.isEmpty()) {
                    children.remove(dir);
                }
            }
        }
    }
}
