package com.kursk1000

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Гейт ViewModel: подлинная метка открывает карточку, поддельная игнорируется ПОЛНОСТЬЮ (ни
 * карточки, ни предупреждения — остаёмся в Searching), а если рядом и реальная, и поддельная на
 * одном UUID — открывается реальная без «дрожания». Sticky-логика держит открытую карточку при
 * пропаже маяка. Всё на фейках, без устройства.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LandmarkViewModelGateTest {

    private val dispatcher = StandardTestDispatcher()

    private val uuid = "A1B2C3D4-E5F6-7890-ABCD-EF1234567890"
    private val secret = "demo-secret".toByteArray()
    private val now = 1_700_000_000_000L
    private val landmark = Landmark(
        uuid = uuid, name = "Знаменский собор", subtitle = "", year = "",
        summary = "", coverImage = null, sections = emptyList(), facts = emptyList(),
        gallery = emptyList(), publicKey = "",
    )

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    /** hex-пакет Service Data для счётчика текущего окна + [deltaSteps]. */
    private fun authData(deltaSteps: Long = 0): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }
        return BeaconCode.toHex(BeaconCode.payload(mac, BeaconCode.counterAt(now) + deltaSteps))
    }

    private fun validAuthData(): String = authData()

    private fun buildVm(scanner: FakeBleScanner): LandmarkViewModel {
        val repo = FakeLandmarkRepository(LandmarkLoad.Ready(mapOf(uuid to landmark)))
        val verifier = BeaconVerifier(FakeBeaconAuthKeyProvider(mapOf(uuid to secret)), nowMs = { now })
        return LandmarkViewModel(repo, scanner, verifier, MutableStateFlow(true))
    }

    @Test
    fun authenticBeacon_opensCard() = runTest(dispatcher) {
        val scanner = FakeBleScanner()
        val vm = buildVm(scanner)
        advanceUntilIdle()

        scanner.emit(BeaconInfo(uuid, rssi = -50, authData = validAuthData()))
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue("ожидался Loaded, был $state", state is UiState.Loaded)
        assertEquals(landmark, (state as UiState.Loaded).landmark)
    }

    @Test
    fun beaconWithoutCode_isIgnored() = runTest(dispatcher) {
        val scanner = FakeBleScanner()
        val vm = buildVm(scanner)
        advanceUntilIdle()

        // Метка без динамического кода (уязвимая/поддельная) — ноль реакции: остаёмся в поиске.
        scanner.emit(BeaconInfo(uuid, rssi = -50, authData = null))
        advanceUntilIdle()

        assertTrue(vm.uiState.value is UiState.Searching)
    }

    @Test
    fun beaconWithForgedCode_isIgnored() = runTest(dispatcher) {
        val scanner = FakeBleScanner()
        val vm = buildVm(scanner)
        advanceUntilIdle()

        // Поддельный код не проходит verify — карточка не открывается и предупреждения нет.
        scanner.emit(BeaconInfo(uuid, rssi = -50, authData = "01deadbeefdeadbeef"))
        advanceUntilIdle()

        assertTrue(vm.uiState.value is UiState.Searching)
    }

    @Test
    fun loadedCardStaysWhenBeaconDisappears() = runTest(dispatcher) {
        val scanner = FakeBleScanner()
        val vm = buildVm(scanner)
        advanceUntilIdle()

        scanner.emit(BeaconInfo(uuid, rssi = -50, authData = validAuthData()))
        advanceUntilIdle()
        assertTrue(vm.uiState.value is UiState.Loaded)

        // Маяк пропал из эфира — карточка должна остаться (sticky), закрывает только пользователь.
        scanner.emit(null)
        advanceUntilIdle()
        assertTrue(vm.uiState.value is UiState.Loaded)
    }

    @Test
    fun farAuthenticBeacon_staysSearching() = runTest(dispatcher) {
        val scanner = FakeBleScanner()
        val vm = buildVm(scanner)
        advanceUntilIdle()

        // Код подлинный, но сигнал слабый (метка далеко) — карточку не открываем (TZ: только при
        // приближении). На радаре метка всё равно видна через visibleBeacons.
        scanner.emit(BeaconInfo(uuid, rssi = -70, authData = validAuthData()))
        advanceUntilIdle()

        assertTrue(vm.uiState.value is UiState.Searching)
    }

    @Test
    fun replayFloorSurvivesDismiss() = runTest(dispatcher) {
        val scanner = FakeBleScanner()
        val vm = buildVm(scanner)
        advanceUntilIdle()

        // Подходим к подлинной метке: карточка открыта, фиксируется counter текущего окна.
        scanner.emit(BeaconInfo(uuid, rssi = -50, authData = authData()))
        advanceUntilIdle()
        assertTrue(vm.uiState.value is UiState.Loaded)

        // Пользователь закрыл карточку.
        vm.dismissCard()
        advanceUntilIdle()

        // Злоумышленник переигрывает записанный РАНЕЕ пакет (counter-1, всё ещё в окне ±1).
        // Сброс на закрытии принял бы его; теперь анти-replay floor сохранён → пакет не проходит
        // verify и игнорируется: карточка заново НЕ открывается, остаёмся в поиске.
        scanner.emit(BeaconInfo(uuid, rssi = -50, authData = authData(deltaSteps = -1)))
        advanceUntilIdle()
        assertTrue(vm.uiState.value is UiState.Searching)
    }

    @Test
    fun spooferDoesNotDisturbOpenCard() = runTest(dispatcher) {
        val scanner = FakeBleScanner()
        val vm = buildVm(scanner)
        advanceUntilIdle()

        scanner.emit(BeaconInfo(uuid, rssi = -50, authData = validAuthData(), deviceAddress = "AA:AA"))
        advanceUntilIdle()
        assertTrue(vm.uiState.value is UiState.Loaded)

        // Рядом включился спуфер на том же UUID без валидного кода (ближе по RSSI). Раньше это
        // вытесняло карточку предупреждением; теперь — ноль реакции: карточка остаётся открытой.
        scanner.emitAll(listOf(
            BeaconInfo(uuid, rssi = -50, authData = validAuthData(), deviceAddress = "AA:AA"),
            BeaconInfo(uuid, rssi = -40, authData = null, deviceAddress = "BB:BB"),
        ))
        advanceUntilIdle()
        assertTrue(vm.uiState.value is UiState.Loaded)
    }

    @Test
    fun realAndFakeOnSameUuid_opensRealNoFlicker() = runTest(dispatcher) {
        val scanner = FakeBleScanner()
        val vm = buildVm(scanner)
        advanceUntilIdle()

        // Реальная и поддельная метка вещают на ОДНОМ UUID одновременно (две записи — по MAC).
        // Поддельная даже ближе (выше RSSI). Должна открыться реальная: подделка не проходит verify.
        scanner.emitAll(listOf(
            BeaconInfo(uuid, rssi = -55, authData = validAuthData(), deviceAddress = "AA:AA"),
            BeaconInfo(uuid, rssi = -40, authData = "01deadbeefdeadbeef", deviceAddress = "BB:BB"),
        ))
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue("ожидался Loaded, был $state", state is UiState.Loaded)
        assertEquals(landmark, (state as UiState.Loaded).landmark)
    }
}

/** Фейковый сканер: тест сам толкает список [visibleBeacons] (как боевой сканер — по устройству
 *  на запись). Остальное — заглушки. */
private class FakeBleScanner : BleScanner {
    private val _visible = MutableStateFlow<List<BeaconInfo>>(emptyList())
    override val visibleBeacons: StateFlow<List<BeaconInfo>> = _visible.asStateFlow()
    override val scanState: StateFlow<ScanState> = MutableStateFlow(ScanState.Scanning)

    /** Один маяк в эфире (или пусто при null) — удобная обёртка над [emitAll]. */
    fun emit(beacon: BeaconInfo?) { _visible.value = listOfNotNull(beacon) }

    /** Несколько устройств одновременно (напр. реальное + поддельное на одном UUID). */
    fun emitAll(beacons: List<BeaconInfo>) { _visible.value = beacons }

    override fun startScan(allowedUuids: Set<String>) {}
    override fun stopScan() {}
    override fun release() {}
}

/** Фейковый репозиторий: сразу отдаёт готовый список. */
private class FakeLandmarkRepository(state: LandmarkLoad) : LandmarkRepository {
    override val landmarks: StateFlow<LandmarkLoad> = MutableStateFlow(state)
    override suspend fun refresh() {}
}
