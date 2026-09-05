package com.virjar.tk.desktop

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import java.awt.EventQueue
import java.awt.Insets
import java.awt.Rectangle
import java.awt.Toolkit
import javax.swing.Timer

internal enum class DesktopFullscreenBackend { ComposeNative, MacOsStagedComposeNative }

private enum class DesktopPlacementPhase {
    Idle, ComposeExit, MacFloating, MacFrame, MacEnter,
    MacExitInitial, MacExitSettling, MacExitReasserted, MacRestore,
}

internal fun desktopFullscreenBackend(osName: String): DesktopFullscreenBackend =
    if (osName.contains("Mac", ignoreCase = true))
        DesktopFullscreenBackend.MacOsStagedComposeNative
    else DesktopFullscreenBackend.ComposeNative

/** 媒体图库的 placement owner：Maximized <-> Compose/Skiko 原生 Fullscreen。 */
internal class DesktopWindowPlacementOwner(
    private val window: ComposeWindow,
    private val state: WindowState,
    private val fullscreenBackend: DesktopFullscreenBackend,
    private val onPlacementChanged: (WindowPlacement) -> Unit,
) : AutoCloseable {
    private var closed = false
    private var phase = DesktopPlacementPhase.Idle
    private var phaseTicks = 0
    private var stableTicks = 0

    var placement: WindowPlacement = state.placement
        private set

    val effectivePlacement: WindowPlacement get() = window.placement
    val isTransitioning: Boolean get() =
        phase != DesktopPlacementPhase.Idle || placement != effectivePlacement

    private val timer = Timer(TRANSITION_POLL_MILLIS) { tick() }.apply { initialDelay = 0 }

    fun install() {
        check(EventQueue.isDispatchThread()) { "window placement owner must be installed on EDT" }
        window.rootPane.putClientProperty(WINDOW_PLACEMENT_OWNER_PROPERTY, this)
        if (fullscreenBackend == DesktopFullscreenBackend.MacOsStagedComposeNative) {
            window.rootPane.putClientProperty("apple.awt.fullscreenable", true)
        }
        timer.start()
        tick()
    }

    fun enterFullscreen() = requestPlacement(WindowPlacement.Fullscreen)
    fun restoreMaximized() = requestPlacement(WindowPlacement.Maximized)
    private fun requestPlacement(target: WindowPlacement) {
        if (!EventQueue.isDispatchThread()) {
            EventQueue.invokeLater { requestPlacement(target) }
            return
        }
        if (closed || target == placement) return
        publish(target)

        if (fullscreenBackend == DesktopFullscreenBackend.ComposeNative) {
            if (phase == DesktopPlacementPhase.ComposeExit) return
            if (target == WindowPlacement.Fullscreen) {
                applyPlacement(target)
                changePhase(DesktopPlacementPhase.Idle)
            } else {
                beginComposeExit()
            }
            return
        }

        // AppKit 会在 Space 过渡动画完成之前就报告 Fullscreen。该窗口期间的反向切换会被忽略，
        // 因此只保留最新请求的 placement，等原生过渡稳定后由 observeMacEnter 派发它。
        if (phase == DesktopPlacementPhase.MacEnter ||
            phase in DesktopPlacementPhase.MacExitInitial..DesktopPlacementPhase.MacExitReasserted
        ) return
        if (target == WindowPlacement.Fullscreen) {
            beginMacEnter()
        } else {
            beginMacExit()
        }
    }

    override fun close() {
        if (!EventQueue.isDispatchThread()) {
            EventQueue.invokeLater(::close)
            return
        }
        if (closed) return
        closed = true
        timer.stop()
        if (window.rootPane.getClientProperty(WINDOW_PLACEMENT_OWNER_PROPERTY) === this) {
            window.rootPane.putClientProperty(WINDOW_PLACEMENT_OWNER_PROPERTY, null)
        }
        val fullscreenRequestedOrObserved = placement == WindowPlacement.Fullscreen ||
            window.placement == WindowPlacement.Fullscreen
        if (window.isDisplayable && fullscreenRequestedOrObserved) {
            window.placement = WindowPlacement.Floating
        }
    }

    private fun tick() {
        if (closed || !window.isDisplayable) return
        phaseTicks++
        if (phase != DesktopPlacementPhase.Idle && phaseTicks >= MAX_TRANSITION_TICKS) {
            recoverFromTimeout()
            return
        }

        when (phase) {
            DesktopPlacementPhase.Idle -> observeIdlePlacement()
            DesktopPlacementPhase.ComposeExit -> observeComposeExit()
            DesktopPlacementPhase.MacFloating -> observeMacFloating()
            DesktopPlacementPhase.MacFrame -> observeMacFrame()
            DesktopPlacementPhase.MacEnter -> observeMacEnter()
            DesktopPlacementPhase.MacExitInitial,
            DesktopPlacementPhase.MacExitSettling,
            DesktopPlacementPhase.MacExitReasserted,
            -> observeMacExit()
            DesktopPlacementPhase.MacRestore -> observeMacRestore()
        }
    }

    private fun observeIdlePlacement() {
        val observed = window.placement
        if (placement == WindowPlacement.Fullscreen && observed != WindowPlacement.Fullscreen) {
            publish(WindowPlacement.Maximized)
            if (fullscreenBackend == DesktopFullscreenBackend.MacOsStagedComposeNative) {
                beginMacExit()
            } else {
                applyPlacement(WindowPlacement.Maximized)
            }
        } else if (placement == WindowPlacement.Maximized && observed != placement) {
            if (fullscreenBackend == DesktopFullscreenBackend.MacOsStagedComposeNative) {
                beginMacExit()
            } else if (observed == WindowPlacement.Fullscreen) {
                beginComposeExit()
            } else {
                applyPlacement(WindowPlacement.Maximized)
            }
        }
    }

    private fun beginComposeExit() {
        state.placement = WindowPlacement.Floating
        changePhase(DesktopPlacementPhase.ComposeExit)
    }

    private fun observeComposeExit() {
        if (window.placement == WindowPlacement.Fullscreen) return
        applyPlacement(placement)
        changePhase(DesktopPlacementPhase.Idle)
    }

    private fun beginMacEnter() {
        applyPlacement(WindowPlacement.Floating)
        changePhase(DesktopPlacementPhase.MacFloating)
    }

    private fun observeMacFloating() {
        val floating = state.placement == WindowPlacement.Floating &&
            window.placement == WindowPlacement.Floating
        if (!isStable(floating, REQUIRED_SHORT_STABLE_TICKS)) {
            applyPlacement(WindowPlacement.Floating)
            return
        }
        window.bounds = Rectangle(window.graphicsConfiguration.bounds)
        window.validate()
        changePhase(DesktopPlacementPhase.MacFrame)
    }

    private fun observeMacFrame() {
        val screen = window.graphicsConfiguration.bounds
        if (window.bounds != screen) window.bounds = Rectangle(screen)
        if (!isStable(window.bounds == screen, REQUIRED_SHORT_STABLE_TICKS)) return
        window.placement = WindowPlacement.Fullscreen
        changePhase(DesktopPlacementPhase.MacEnter)
    }

    private fun observeMacEnter() {
        val entered = window.placement == WindowPlacement.Fullscreen
        if (!isStable(entered, REQUIRED_MAC_ENTER_STABLE_TICKS)) return
        state.placement = WindowPlacement.Fullscreen
        if (placement == WindowPlacement.Fullscreen) {
            changePhase(DesktopPlacementPhase.Idle)
            refreshContent()
        } else {
            beginMacExit()
        }
    }

    private fun beginMacExit() {
        val wasFullscreen = window.placement == WindowPlacement.Fullscreen
        if (wasFullscreen) window.placement = WindowPlacement.Floating
        val exitPhase = if (wasFullscreen) DesktopPlacementPhase.MacExitInitial
        else DesktopPlacementPhase.MacExitSettling
        changePhase(exitPhase)
    }

    private fun observeMacExit() {
        if (window.placement == WindowPlacement.Fullscreen) {
            stableTicks = 0
            if (phase == DesktopPlacementPhase.MacExitSettling) {
                // 一次被取消的 enter 在我们已观测到其 Floating 状态之后才落地。
                // 只再排一次原生 exit；反复切换会让 AppKit 抖动。
                window.placement = WindowPlacement.Floating
                changePhase(DesktopPlacementPhase.MacExitReasserted)
            }
            return
        }
        if (phase == DesktopPlacementPhase.MacExitInitial) {
            changePhase(DesktopPlacementPhase.MacExitSettling)
        }
        if (!isStable(condition = true, requiredTicks = REQUIRED_MAC_EXIT_TICKS)) return
        if (placement == WindowPlacement.Fullscreen) {
            beginMacEnter()
        } else {
            reapplyMaximized()
            changePhase(DesktopPlacementPhase.MacRestore)
        }
    }

    private fun observeMacRestore() {
        val settled = state.placement == WindowPlacement.Maximized &&
            window.placement == WindowPlacement.Maximized &&
            window.bounds == maximizedBounds()
        if (isStable(settled, REQUIRED_RESTORE_STABLE_TICKS)) {
            changePhase(DesktopPlacementPhase.Idle)
            refreshContent()
        } else if (phaseTicks % RESTORE_REASSERT_INTERVAL_TICKS == 0) {
            reapplyMaximized()
        }
    }

    private fun recoverFromTimeout() {
        if (window.placement == WindowPlacement.Fullscreen) {
            state.placement = WindowPlacement.Fullscreen
            publish(WindowPlacement.Fullscreen)
            changePhase(DesktopPlacementPhase.Idle)
        } else if (fullscreenBackend == DesktopFullscreenBackend.ComposeNative) {
            applyPlacement(WindowPlacement.Maximized)
            publish(WindowPlacement.Maximized)
            changePhase(DesktopPlacementPhase.Idle)
        } else {
            reapplyMaximized()
            publish(WindowPlacement.Maximized)
            changePhase(DesktopPlacementPhase.MacRestore)
        }
        refreshContent()
    }

    private fun reapplyMaximized() {
        applyPlacement(WindowPlacement.Floating)
        applyPlacement(WindowPlacement.Maximized)
        val expected = maximizedBounds()
        if (window.bounds != expected) window.bounds = expected
    }

    private fun maximizedBounds(): Rectangle {
        val config = window.graphicsConfiguration
        return desktopScreenWorkArea(
            config.bounds,
            Toolkit.getDefaultToolkit().getScreenInsets(config),
        )
    }

    private fun applyPlacement(value: WindowPlacement) {
        state.placement = value
        window.placement = value
    }
    private fun isStable(condition: Boolean, requiredTicks: Int): Boolean {
        stableTicks = if (condition) stableTicks + 1 else 0
        return stableTicks >= requiredTicks
    }
    private fun changePhase(value: DesktopPlacementPhase) {
        phase = value
        phaseTicks = 0
        stableTicks = 0
    }

    private fun publish(value: WindowPlacement) {
        if (placement == value) return
        placement = value
        onPlacementChanged(value)
    }

    private fun refreshContent() {
        window.validate()
        window.rootPane.revalidate()
        window.rootPane.repaint()
    }
}

internal fun ComposeWindow.desktopWindowPlacementOwner(): DesktopWindowPlacementOwner? =
    rootPane.getClientProperty(WINDOW_PLACEMENT_OWNER_PROPERTY) as? DesktopWindowPlacementOwner

internal fun desktopScreenWorkArea(screen: Rectangle, insets: Insets) = Rectangle(
    screen.x + insets.left, screen.y + insets.top,
    (screen.width - insets.left - insets.right).coerceAtLeast(0),
    (screen.height - insets.top - insets.bottom).coerceAtLeast(0),
)

private const val WINDOW_PLACEMENT_OWNER_PROPERTY = "teamtalk.desktop.windowPlacementOwner"
private const val TRANSITION_POLL_MILLIS = 100
private const val MAX_TRANSITION_TICKS = 50
private const val REQUIRED_SHORT_STABLE_TICKS = 2
private const val REQUIRED_MAC_ENTER_STABLE_TICKS = 12
private const val REQUIRED_MAC_EXIT_TICKS = 12
private const val REQUIRED_RESTORE_STABLE_TICKS = 5
private const val RESTORE_REASSERT_INTERVAL_TICKS = 10
