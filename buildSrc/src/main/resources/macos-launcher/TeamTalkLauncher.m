// TeamTalk macOS 原生启动器（universal x86_64 + arm64）。
//
// 结构对齐 JetBrains Toolbox / Conveyor：本二进制是 Info.plist 声明的唯一主进程，
// 在自身进程内以 JNI 方式启动 JVM——没有 exec 替换、没有子进程。macOS 26 (Tahoe)
// 要求菜单栏 NSStatusItem 与通知身份归属 bundle 主进程；shell exec java 的模型会被
// 静默拒绝渲染托盘。JVM 选项与主类来自本 bundle 的 Info.plist，由打包任务写入。
// 编译需要 JDK 的 include/jni.h（构建机 Gradle JVM 提供，仅编译期依赖）。
//
// 主线程运行 AppKit run loop（AWT 的 macOS 实现依赖）；
// Java main 在专用线程启动，结束后请求主循环退出并透传退出码。

#import <Cocoa/Cocoa.h>
#import <UserNotifications/UserNotifications.h>
#import <dlfcn.h>
#import <pthread.h>
#import <sys/sysctl.h>
#import <sys/types.h>
#import <mach/machine.h>
#import <string.h>
#import <stdlib.h>
#import <stdio.h>
#import <unistd.h>
#import <jni.h>

static const int EXIT_INCOMPATIBLE_ARCHITECTURE = 126;
static const int EXIT_LAUNCH_FAILURE = 125;

// libjvm 导出符号的 dlsym 原型（jni.h 中它是 JavaVM 接口方法，无独立函数类型）。
typedef jint (*CreateJavaVM_t)(JavaVM **, JNIEnv **, void *);

// ── 架构判定：读取 libjvm.dylib 的 Mach-O 头，与当前 CPU（含 Rosetta 翻译）对比 ──

// sysctl hw.cputype 不带 ABI64 位（x86 报 0x7），Mach header 带（x86_64 = 0x01000007）；
// 统一补齐后再比较。
static cpu_type_t normalizeCpuType(cpu_type_t type) {
    if (type == CPU_TYPE_X86 || type == CPU_TYPE_ARM) return type | 0x01000000;
    return type;
}

static cpu_type_t currentCpuType(BOOL *translated) {
    int value = 0;
    size_t size = sizeof(value);
    *translated = (sysctlbyname("sysctl.proc_translated", &value, &size, NULL, 0) == 0 && value == 1);
    cpu_type_t host;
    size = sizeof(host);
    if (sysctlbyname("hw.cputype", &host, &size, NULL, 0) != 0) return CPU_TYPE_ANY;
    // Rosetta 报告的 hw.cputype 是 x86_64；原生 Apple Silicon 报告 arm64。
    return normalizeCpuType(host);
}

static cpu_type_t machHeaderCpuType(const char *path) {
    FILE *file = fopen(path, "rb");
    if (!file) return CPU_TYPE_ANY;
    struct { uint32_t magic; cpu_type_t cputype; cpu_subtype_t cpusubtype; } header;
    size_t read = fread(&header, 1, sizeof(header), file);
    fclose(file);
    if (read != sizeof(header)) return CPU_TYPE_ANY;
    if (header.magic == 0xfeedfacf) return header.cputype; // MH_MAGIC_64，本机端
    return CPU_TYPE_ANY;
}

// ── 错误对话框：等价于此前 shell 版的 osascript 脚本，非终端启动时使用 ──
static NSString *appleScriptLiteral(NSString *value) {
    NSMutableString *out = [NSMutableString string];
    for (NSUInteger i = 0; i < value.length; i++) {
        unichar c = [value characterAtIndex:i];
        if (c == '"' || c == '\\') [out appendFormat:@"\\%C", c];
        else [out appendFormat:@"%C", c];
    }
    return out;
}

static void showArchitectureDialog(NSString *title, NSString *message, NSString *url) {
    NSString *source = [NSString stringWithFormat:
        @"on run\n"
        @"set response to display dialog %@ with title %@ buttons {\"关闭\", \"打开下载页\"} default button \"打开下载页\" with icon stop\n"
        @"if button returned of response is \"打开下载页\" then open location %@\n"
        @"end run",
        appleScriptLiteral(message), appleScriptLiteral(title), appleScriptLiteral(url)];
    NSDictionary *error = nil;
    [[[NSAppleScript alloc] initWithSource:source] executeAndReturnError:&error];
}

// ── JVM 线程 ──
typedef struct {
    int argc;
    char **argv;
    int status;
    void *jvmLibrary;
} JavaThreadArgs;

static void requestRunLoopStop() {
    // NSApp stop: 只有在 run loop 醒着且有事件时才被拾取；必须显式唤醒，
    // 否则负载结束（含错误路径）后主线程可能永远停在 run loop 里，进程挂住。
    [NSApp performSelectorOnMainThread:@selector(stop:) withObject:nil waitUntilDone:NO];
    CFRunLoopWakeUp((CFRunLoopRef)[NSRunLoop mainRunLoop].getCFRunLoop);
}

// ── 壳→Java 回调：Dock 点击/应用激活/通知点击统一触发主窗口恢复 ──
static JavaVM *gJvm = NULL;
static jclass gBridgeClass = NULL;
static jmethodID gActivationMethod = NULL;

static void notifyJavaUserActivation() {
    if (!gJvm || !gBridgeClass || !gActivationMethod) return;
    JNIEnv *env = NULL;
    jint state = (*gJvm)->GetEnv(gJvm, (void **)&env, JNI_VERSION_1_8);
    if (state == JNI_EDETACHED) {
        // 临时线程：调用后恢复原状。
        if ((*gJvm)->AttachCurrentThread(gJvm, (void **)&env, NULL) == JNI_OK) {
            (*env)->CallStaticVoidMethod(env, gBridgeClass, gActivationMethod);
            (*gJvm)->DetachCurrentThread(gJvm);
        }
        return;
    }
    if (state == JNI_OK) {
        // 已附着线程（如 AWT 渲染所在的主线程）绝不能 detach，否则 AWT 缓存的
        // JNIEnv 失效，下一次 Metal blit 回调即崩溃。
        (*env)->CallStaticVoidMethod(env, gBridgeClass, gActivationMethod);
    }
}

// ── 系统通知：优先 UNUserNotificationCenter（以应用 bundle 身份、图标跟随 app）；
//    未授权或被系统拒绝（如签名不被信任）时回退 osascript，保证通知可达。 ──
static BOOL gNotificationAuthRequested = NO;

@interface LauncherAppDelegate : NSObject <NSApplicationDelegate>
@end

// NSApp.delegate 是 weak 引用，须强持有实例。
static LauncherAppDelegate *gAppDelegate = nil;

@implementation LauncherAppDelegate
// Dock 点击（应用已在前台、窗口隐藏）走 applicationShouldHandleReopen，不是激活通知。
// AWT 稍后若替换 delegate，其 reopen 事件同样接入 Java 侧 AppReopenedListener；两条路径等价。
- (BOOL)applicationShouldHandleReopen:(NSApplication *)sender hasVisibleWindows:(BOOL)flag {
    notifyJavaUserActivation();
    return NO; // 窗口显示完全由 Java 侧窗口状态机决定。
}
@end

@interface LauncherNotificationCenterDelegate : NSObject <UNUserNotificationCenterDelegate>
@end

// UNCenter.delegate 是 weak 引用，必须另行强持有，否则对象赋值后即被释放。
static LauncherNotificationCenterDelegate *gNotificationDelegate = nil;

@implementation LauncherNotificationCenterDelegate
// 应用前台时仍以横幅+声音+通知中心呈现；投递时机由 Java 侧窗口失焦门禁控制。
- (void)userNotificationCenter:(UNUserNotificationCenter *)center
    willPresentNotification:(UNNotification *)notification
    withCompletionHandler:(void (^)(UNNotificationPresentationOptions))handler {
    handler(UNNotificationPresentationOptionBanner | UNNotificationPresentationOptionList |
            UNNotificationPresentationOptionSound);
}

// 点击通知：激活应用并回调 Java 唤出主窗口（AWT 旧 NSUserNotification 点击链路已随 API 移除）。
- (void)userNotificationCenter:(UNUserNotificationCenter *)center
    didReceiveNotificationResponse:(UNNotificationResponse *)response
    withCompletionHandler:(void (^)(void))handler {
    [NSApp activateIgnoringOtherApps:YES];
    notifyJavaUserActivation();
    handler();
}
@end

static void spawnOsascriptNotification(NSString *title, NSString *body) {
    NSString *script = [NSString stringWithFormat:
        @"display notification \"%@\" with title \"%@\" sound name \"default\"",
        appleScriptLiteral(body), appleScriptLiteral(title)];
    NSTask *task = [NSTask new];
    task.launchPath = @"/usr/bin/osascript";
    task.arguments = @[ @"-e", script ];
    task.standardOutput = [NSPipe pipe];
    task.standardError = [NSPipe pipe];
    @try { [task launch]; } @catch (NSException *e) {
        fprintf(stderr, "TeamTalk launcher: osascript fallback failed (%s)\n", e.reason.UTF8String);
    }
}

static void deliverMacNotification(NSString *title, NSString *body) {
    UNUserNotificationCenter *center = [UNUserNotificationCenter currentNotificationCenter];
    void (^fallback)(void) = ^{ spawnOsascriptNotification(title, body); };
    void (^send)(void) = ^{
        UNMutableNotificationContent *content = [UNMutableNotificationContent new];
        content.title = title;
        content.body = body;
        content.sound = [UNNotificationSound defaultSound];
        UNNotificationRequest *request = [UNNotificationRequest
            requestWithIdentifier:[NSUUID UUID].UUIDString content:content trigger:nil];
        [center addNotificationRequest:request withCompletionHandler:^(NSError *error) {
            if (error) fallback();
        }];
    };
    if (!gNotificationAuthRequested) {
        gNotificationAuthRequested = YES;
        [center requestAuthorizationWithOptions:(UNAuthorizationOptionAlert | UNAuthorizationOptionSound)
            completionHandler:^(BOOL granted, NSError *error) {
                if (granted) send(); else fallback();
            }];
    } else {
        send();
    }
}

static void javaPostMacNotification(JNIEnv *env, jclass cls, jstring jTitle, jstring jBody) {
    const char *title = (*env)->GetStringUTFChars(env, jTitle, NULL);
    const char *body = (*env)->GetStringUTFChars(env, jBody, NULL);
    NSString *nsTitle = [NSString stringWithUTF8String:title ?: ""];
    NSString *nsBody = [NSString stringWithUTF8String:body ?: ""];
    if (title) (*env)->ReleaseStringUTFChars(env, jTitle, title);
    if (body) (*env)->ReleaseStringUTFChars(env, jBody, body);
    // UI 框架调用统一派发到主线程。
    dispatch_async(dispatch_get_main_queue(), ^{
        deliverMacNotification(nsTitle, nsBody);
    });
}

static void *javaMain(void *opaque) {
    JavaThreadArgs *args = (JavaThreadArgs *)opaque;
    @autoreleasepool {
        NSBundle *bundle = [NSBundle mainBundle];
        NSString *mainClass = [bundle objectForInfoDictionaryKey:@"TeamTalkMainClass"];
        NSArray<NSString *> *fixedOptions = [bundle objectForInfoDictionaryKey:@"TeamTalkJVMOptions"];
        if (mainClass.length == 0 || ![fixedOptions isKindOfClass:[NSArray class]]) {
            fprintf(stderr, "TeamTalk launcher: Info.plist misses TeamTalkMainClass/TeamTalkJVMOptions\n");
            args->status = EXIT_LAUNCH_FAILURE;
            requestRunLoopStop();
            return NULL;
        }

        // 固定选项（plist）+ 启动器路径（更新器约定重启即执行本二进制）
        // + TEAMTALK_JAVA_OPTS（按空白分词，与旧 shell 语义一致）。
        NSMutableArray<NSString *> *all = [NSMutableArray arrayWithArray:fixedOptions];
        [all addObject:[NSString stringWithFormat:@"-Dteamtalk.shell.launcher=%@", bundle.executablePath]];
        const char *envOptions = getenv("TEAMTALK_JAVA_OPTS");
        if (envOptions && *envOptions) {
            char *copy = strdup(envOptions);
            for (char *token = strtok(copy, " \t"); token; token = strtok(NULL, " \t")) {
                [all addObject:[NSString stringWithUTF8String:token]];
            }
            free(copy);
        }

        struct JavaVMOption *options = calloc(all.count, sizeof(struct JavaVMOption));
        for (NSUInteger i = 0; i < all.count; i++) options[i].optionString = (char *)all[i].UTF8String;
        struct JavaVMInitArgs initArgs;
        memset(&initArgs, 0, sizeof(initArgs));
        initArgs.version = JNI_VERSION_1_8;
        initArgs.nOptions = (jint)all.count;
        initArgs.options = options;
        initArgs.ignoreUnrecognized = JNI_FALSE;

        JavaVM *jvm = NULL;
        JNIEnv *env = NULL;
        CreateJavaVM_t create = (CreateJavaVM_t)dlsym(args->jvmLibrary, "JNI_CreateJavaVM");
        jint created = create ? create(&jvm, &env, &initArgs) : JNI_ERR;
        free(options);
        if (created != JNI_OK || !env) {
            fprintf(stderr, "TeamTalk launcher: JNI_CreateJavaVM failed (%d)\n", created);
            args->status = EXIT_LAUNCH_FAILURE;
            requestRunLoopStop();
            return NULL;
        }

        // JDK 9+ 的 JNI 启动不解析 -Djava.class.path；用 URLClassLoader 挂载 bootstrap.jar，
        // 再反射调用主类 main。loader 的 parent 是系统 AppClassLoader，与旧 shell 语义一致。
        NSString *bootstrapJar = [bundle.bundlePath
            stringByAppendingPathComponent:@"Contents/app/bootstrap.jar"];
        jclass fileClass = (*env)->FindClass(env, "java/io/File");
        jmethodID fileCtor = fileClass ? (*env)->GetMethodID(env, fileClass, "<init>", "(Ljava/lang/String;)V") : NULL;
        jobject file = fileCtor ? (*env)->NewObject(env, fileClass, fileCtor, (*env)->NewStringUTF(env, bootstrapJar.UTF8String)) : NULL;
        jmethodID toURI = file ? (*env)->GetMethodID(env, fileClass, "toURI", "()Ljava/net/URI;") : NULL;
        jobject uri = toURI ? (*env)->CallObjectMethod(env, file, toURI) : NULL;
        jclass uriClass = uri ? (*env)->GetObjectClass(env, uri) : NULL;
        jmethodID toURL = uriClass ? (*env)->GetMethodID(env, uriClass, "toURL", "()Ljava/net/URL;") : NULL;
        jobject url = toURL ? (*env)->CallObjectMethod(env, uri, toURL) : NULL;
        jclass urlClass = url ? (*env)->FindClass(env, "java/net/URL") : NULL;
        jclass loaderClass = urlClass ? (*env)->FindClass(env, "java/net/URLClassLoader") : NULL;
        jmethodID newLoader = loaderClass
            ? (*env)->GetStaticMethodID(env, loaderClass, "newInstance", "([Ljava/net/URL;)Ljava/net/URLClassLoader;")
            : NULL;
        jobjectArray urls = (url && urlClass) ? (*env)->NewObjectArray(env, 1, urlClass, NULL) : NULL;
        if (urls) (*env)->SetObjectArrayElement(env, urls, 0, url);
        jobject loader = newLoader ? (*env)->CallStaticObjectMethod(env, loaderClass, newLoader, urls) : NULL;
        jmethodID loadClass = loaderClass ? (*env)->GetMethodID(env, loaderClass, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;") : NULL;
        jclass bootstrapClass = (loader && loadClass)
            ? (jclass)(*env)->CallObjectMethod(env, loader, loadClass, (*env)->NewStringUTF(env, mainClass.UTF8String))
            : NULL;
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionDescribe(env);
        if (!bootstrapClass) {
            fprintf(stderr, "TeamTalk launcher: cannot load %s from %s\n", mainClass.UTF8String, bootstrapJar.UTF8String);
            args->status = EXIT_LAUNCH_FAILURE;
            requestRunLoopStop();
            return NULL;
        }
        jmethodID mainMethod = (*env)->GetStaticMethodID(env, bootstrapClass, "main", "([Ljava/lang/String;)V");
        if (!mainMethod) {
            if ((*env)->ExceptionCheck(env)) (*env)->ExceptionDescribe(env);
            fprintf(stderr, "TeamTalk launcher: cannot resolve %s.main(String[])\n", mainClass.UTF8String);
            args->status = EXIT_LAUNCH_FAILURE;
            requestRunLoopStop();
            return NULL;
        }

        // 绑定壳能力（系统通知 + 用户唤起回调）到 bootstrap 的 DesktopNativeBridge。
        jclass bridgeClass = (jclass)(*env)->CallObjectMethod(env, loader, loadClass,
            (*env)->NewStringUTF(env, "com.virjar.tk.desktop.shell.DesktopNativeBridge"));
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        if (bridgeClass) {
            JNINativeMethod bridgeMethods[] = {
                {(char *)"postMacNotification", (char *)"(Ljava/lang/String;Ljava/lang/String;)V",
                    (void *)&javaPostMacNotification},
            };
            if ((*env)->RegisterNatives(env, bridgeClass, bridgeMethods, 1) == JNI_OK) {
                jmethodID markResolved = (*env)->GetStaticMethodID(env, bridgeClass, "markResolved", "()V");
                if (markResolved) (*env)->CallStaticVoidMethod(env, bridgeClass, markResolved);

                // 保存回调目标：Dock 点击/通知点击经 notifyJavaUserActivation 回进 Java。
                jmethodID activation = (*env)->GetStaticMethodID(env, bridgeClass, "onMacUserActivation", "()V");
                if (activation) {
                    gJvm = jvm;
                    gBridgeClass = (jclass)(*env)->NewGlobalRef(env, bridgeClass);
                    gActivationMethod = activation;
                }
            }
            if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        }
        jclass stringClass = (*env)->FindClass(env, "java/lang/String");
        jobjectArray javaArgs = (*env)->NewObjectArray(env, (jint)args->argc, stringClass, NULL);
        for (int i = 0; i < args->argc; i++) {
            (*env)->SetObjectArrayElement(env, javaArgs, (jint)i, (*env)->NewStringUTF(env, args->argv[i]));
        }
        (*env)->CallStaticVoidMethod(env, bootstrapClass, mainMethod, javaArgs);
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionDescribe(env);
        // Java main 返回即负载结束；销毁 JVM 并让主线程退出 run loop。
        (*jvm)->DestroyJavaVM(jvm);
        requestRunLoopStop();
        return NULL;
    }
}

int main(int argc, char *argv[]) {
    @autoreleasepool {
        NSBundle *bundle = [NSBundle mainBundle];
        if (!bundle.executablePath) {
            fprintf(stderr, "TeamTalk launcher: cannot resolve bundle executable path\n");
            return EXIT_LAUNCH_FAILURE;
        }
        NSString *libjvm = [bundle.bundlePath
            stringByAppendingPathComponent:@"Contents/runtime/Contents/Home/lib/server/libjvm.dylib"];

        // 架构检查（双向拒绝）：Rosetta 正被 Apple 淘汰（未来 macOS 将移除），JVM 在翻译层
        // 下性能与稳定性都不理想；错包一律弹窗引导下载正确架构版本。
        BOOL translated = NO;
        cpu_type_t host = currentCpuType(&translated);
        cpu_type_t runtime = machHeaderCpuType(libjvm.fileSystemRepresentation);
        BOOL mismatch = NO;
        if (runtime != CPU_TYPE_ANY) {
            cpu_type_t nativeHost = translated ? CPU_TYPE_ARM64 : host; // 翻译态的真实硬件
            mismatch = (runtime != nativeHost);
        }
        if (mismatch) {
            NSString *arch = (runtime == CPU_TYPE_ARM64) ? @"Apple 芯片" : @"Intel 芯片";
            NSString *message = [NSString stringWithFormat:
                @"此安装包适用于 %@，无法在当前 Mac 上运行。请下载对应的 macOS 版本。", arch];
            NSString *server = [bundle objectForInfoDictionaryKey:@"TeamTalkDownloadBaseURL"];
            NSString *url = [[server ?: @"" stringByTrimmingCharactersInSet:
                [NSCharacterSet whitespaceAndNewlineCharacterSet]]
                stringByTrimmingCharactersInSet:[NSCharacterSet characterSetWithCharactersInString:@"/"]];
            url = [NSString stringWithFormat:@"%@/#download", url];
            fprintf(stderr, "%s\n%s\n", message.UTF8String, url.UTF8String);
            if (!isatty(STDERR_FILENO)) {
                NSString *title = [bundle objectForInfoDictionaryKey:@"CFBundleDisplayName"] ?: @"TeamTalk";
                showArchitectureDialog(title, message, url);
            }
            return EXIT_INCOMPATIBLE_ARCHITECTURE;
        }

        void *jvmLibrary = dlopen(libjvm.fileSystemRepresentation, RTLD_LAZY | RTLD_GLOBAL);
        if (!jvmLibrary) {
            fprintf(stderr, "TeamTalk launcher: cannot load JVM (%s)\n", dlerror());
            return EXIT_LAUNCH_FAILURE;
        }

        [NSApplication sharedApplication];
        // 通知 delegate 与应用激活观察必须挂在主线程的 AppKit 状态上。
        dispatch_async(dispatch_get_main_queue(), ^{
            gAppDelegate = [[LauncherAppDelegate alloc] init];
            NSApp.delegate = gAppDelegate;
            gNotificationDelegate = [[LauncherNotificationCenterDelegate alloc] init];
            [UNUserNotificationCenter currentNotificationCenter].delegate = gNotificationDelegate;
            // Dock 点击/恢复激活（AWT 的 reopen 桥接在 JNI 嵌入下不完整）：
            // 应用从非活跃转为活跃即视为用户唤起，交由 Java 决定是否显示窗口。
            [[NSNotificationCenter defaultCenter] addObserverForName:NSApplicationDidBecomeActiveNotification
                object:nil queue:[NSOperationQueue mainQueue]
                usingBlock:^(NSNotification *note) { notifyJavaUserActivation(); }];
        });
        // AWT 依赖主线程的 AppKit 循环；先起 JVM 线程再进入 run loop。
        JavaThreadArgs threadArgs = { argc - 1, argv + 1, 0, jvmLibrary };
        pthread_t javaThread;
        pthread_create(&javaThread, NULL, javaMain, &threadArgs);
        [NSApp run];
        pthread_join(javaThread, NULL);
        dlclose(jvmLibrary);
        return threadArgs.status;
    }
}
