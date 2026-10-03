package com.virjar.tk.desktop.call

import org.junit.Assume.assumeTrue

/**
 * 真实引擎测试的环境守卫（2f7cc72b 首创于环回测试，压缩批次新增的引擎测试曾漏带）。
 *
 * libwebrtc 音频采集依赖真实音频设备。GitHub Actions 的 Linux runner 无音频子系统：
 * 枚举可能挂起、返回 dummy 或 ADM 初始化阻塞，结果都不可信——直接跳过，真实引擎
 * 测试只在本地开发机（有真实声卡）执行。设备枚举放守护线程限时，防其他无头环境挂起。
 */
internal fun assumeRealAudioEngineEnvironment() {
    val headlessLinuxCi = System.getenv("CI") == "true" &&
        System.getProperty("os.name").lowercase().contains("linux")
    assumeTrue("真实引擎测试不在无音频子系统的 Linux CI 上执行", !headlessLinuxCi)
    var audioDevicesAvailable = false
    val probe = Thread {
        audioDevicesAvailable = runCatching {
            dev.onvoid.webrtc.media.MediaDevices.getAudioCaptureDevices().isNotEmpty()
        }.getOrDefault(false)
    }.apply { isDaemon = true }
    probe.start()
    probe.join(5_000)
    assumeTrue(
        "真实引擎测试需要音频采集设备（枚举超时或无设备，无头环境跳过）",
        !probe.isAlive && audioDevicesAvailable,
    )
}
