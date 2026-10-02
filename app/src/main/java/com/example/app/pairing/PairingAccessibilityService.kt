package com.example.app.pairing

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Управление автосопряжением Shizuku: запуск/остановка сценария и поток лога для вкладки «Настройка».
 */
object PairingAutomation {

    enum class StartResult { STARTED, SERVICE_DISABLED, ALREADY_RUNNING }

    /** Какой сценарий сейчас выполняется. */
    enum class Task { PAIR, DEV_UNLOCK, USB_DEBUG, USB_DEBUG_OFF }

    /**
     * Лог сценария. Хранится целиком (а не одноразовым потоком), чтобы строки, которые сценарий пишет,
     * пока приложение свёрнуто (открыты «Настройки»), не терялись и появлялись при возврате.
     */
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    private val _task = MutableStateFlow<Task?>(null)
    val task: StateFlow<Task?> = _task

    private var job: Job? = null

    internal fun say(line: String) {
        _lines.update { (it + line).takeLast(500) }
    }

    /** Сопряжение Shizuku. */
    fun start(): StartResult = launch(Task.PAIR) { PairingScript(it).run() }

    /** Включение меню разработчика (7 нажатий на «Номер сборки»). */
    fun startDeveloperUnlock(): StartResult = launch(Task.DEV_UNLOCK) { DeveloperUnlockScript(it).run() }

    /** Включение «Отладки по USB» (переключатель в «Для разработчиков»). */
    fun startUsbDebug(): StartResult = launch(Task.USB_DEBUG) { UsbDebugScript(it, enable = true).run() }

    /** Выключение «Отладки по USB». */
    fun startUsbDebugOff(): StartResult = launch(Task.USB_DEBUG_OFF) { UsbDebugScript(it, enable = false).run() }

    private fun launch(
        t: Task,
        block: suspend (PairingAccessibilityService) -> Unit
    ): StartResult {
        val service = PairingAccessibilityService.instance ?: return StartResult.SERVICE_DISABLED
        if (job?.isActive == true) return StartResult.ALREADY_RUNNING

        _lines.value = emptyList()
        _task.value = t
        _running.value = true
        job = service.scope.launch {
            try {
                block(service)
            } catch (e: CancellationException) {
                say("■ Остановлено")
                throw e
            } catch (e: Throwable) {
                say("✗ Ошибка: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                _running.value = false
                _task.value = null
            }
        }
        return StartResult.STARTED
    }

    fun stop() {
        job?.cancel()
    }
}

/**
 * Служба доступности: даёт сценарию [PairingScript] читать экран и нажимать кнопки
 * в Shizuku, «Настройках» и шторке уведомлений. Включается один раз вручную.
 */
class PairingAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: PairingAccessibilityService? = null
            private set
    }

    var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        PairingAutomation.stop()
        scope.cancel()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    // ---------- вспомогательные функции для сценария ----------

    /** Корни всех видимых окон (приложение, диалог, шторка уведомлений). */
    private fun roots(): List<AccessibilityNodeInfo> {
        val fromWindows = runCatching { windows.mapNotNull { it.root } }.getOrDefault(emptyList())
        return fromWindows.ifEmpty { listOfNotNull(rootInActiveWindow) }
    }

    /** Все узлы на экране, подходящие под условие. */
    fun collect(pred: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo) {
            if (pred(n)) out.add(n)
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { walk(it) }
            }
        }
        roots().forEach { walk(it) }
        return out
    }

    fun first(pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? = collect(pred).firstOrNull()

    /** Нажимает на узел или на ближайшего кликабельного родителя; запасной вариант — тап по координатам. */
    fun click(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        while (n != null) {
            if (n.isClickable && n.isEnabled && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent
        }
        return tap(node)
    }

    /** Тап строго по координатам центра узла (не поднимается к родителю, как click). */
    fun tap(node: AccessibilityNodeInfo): Boolean {
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.isEmpty) return false
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** Тап по экранным координатам (для элементов, которые Android не показывает службе доступности). */
    fun tapAt(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** Прокрутка списка ТОЛЬКО в окне указанного пакета (чужие приложения не трогаем). */
    fun scroll(pkg: String, forward: Boolean): Boolean {
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return first { it.isScrollable && it.packageName?.toString() == pkg }?.performAction(action) ?: false
    }

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun openShade(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    /** Короткая выжимка с экрана — для лога, когда сценарий застрял. */
    fun dumpScreen(): String =
        collect { !it.text.isNullOrBlank() || !it.contentDescription.isNullOrBlank() }
            .map { (it.text ?: it.contentDescription).toString().trim().take(40) }
            .distinct()
            .take(14)
            .joinToString(" | ")
}
