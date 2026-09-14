package com.virjar.tk.desktop.shell

/**
 * 原生壳能力桥。方法由 macOS 原生启动器在 JVM 启动后通过 RegisterNatives 绑定，
 * 绑定成功由壳回调 [markResolved]；开发/裸 JVM 运行没有壳，[available] 恒为 false，
 * 调用方据此回退其他通道。
 */
object DesktopNativeBridge {

    @JvmStatic
    @Volatile
    private var resolved = false

    /** 原生壳绑定的系统通知入口；身份与图标跟随应用 bundle。 */
    @JvmStatic
    external fun postMacNotification(title: String, body: String)

    /** 仅由原生壳在 RegisterNatives 成功后回调。 */
    @JvmStatic
    fun markResolved() {
        resolved = true
    }

    /** 是否可安全调用 [postMacNotification]。 */
    @JvmStatic
    fun available(): Boolean = resolved

    @JvmStatic
    @Volatile
    private var activationHandler: (() -> Unit)? = null

    /**
     * 原生壳的用户唤起回调（Dock 点击、通知点击、切回应用）；
     * 由壳在任意线程调用，处理器必须自行派发到 UI 线程。
     */
    @JvmStatic
    fun onMacUserActivation() {
        activationHandler?.invoke()
    }

    /** 登录会话注册主窗口恢复动作；窗口生命周期结束时置回 null。 */
    @JvmStatic
    fun setMacActivationHandler(handler: (() -> Unit)?) {
        activationHandler = handler
    }
}
