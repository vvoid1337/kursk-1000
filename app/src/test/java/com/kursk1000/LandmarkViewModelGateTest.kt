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
 * Гейт ViewModel: подлинная метка открывает карточку, неподтверждённая — даёт [UiState.Untrusted],
 * а sticky-логика держит открытую карточку при пропаже маяка. Всё на фейках, без устройства.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LandmarkViewModelGateTest {

    private val dispatcher = StandardTestDispatcher()

    private val uuid = "A1B2C3D4-E5F6-7890-ABCD-EF1234567890"
    private val secret = "demo-secret".toByteArray()
    private val now = 1_700_000_000_000L
    private val landmark = Landmark(
        uuid = uuid, name = "Знаменский собор", emoji = "⛪", subtitle = "", year = "",
        summary = "", coverImage = null, sections = emptyList(), facts = emptyList(),
        gallery = emptyList(), publicKey = "",
    )

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun validAuthData(): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret, "HmacSHA256")) }
        return BeaconCode.toHex(BeaconCode.payload(mac, BeaconCode.counterAt(now)))
    }

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
    fun beaconWithoutCode_isUntrusted() = runTest(dispatcher) {
        val scanner = FakeBleScanner()
        val vm = buildVm(scanner)
        advanceUntilIdle()

        scanner.emit(BeaconInfo(uuid, rssi = -50, authData = null))
        advanceUntilIdle()

        assertTrue(vm.uiState.value is UiState.Untrusted)
    }

    @Test
    fun beaconWithForgedCode_isUntrusted() = runTest(dispatcher) {
        val scanner = FakeBleScanner()
        val vm = buildVm(scanner)
        advanceUntilIdle()

        scanner.emit(BeaconInfo(uuid, rssi = -50, authData = "01deadbeefdeadbeef"))
        advanceUntilIdle()

        assertTrue(vm.uiState.value is UiState.Untrusted)
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
    fun untrustedEvictsOpenCard() = runTest(dispatcher) {
        val scanner = FakeBleScanner()
        val vm = buildVm(scanner)
        advanceUntilIdle()

        scanner.emit(BeaconInfo(uuid, rssi = -50, authData = validAuthData()))
        advanceUntilIdle()
        assertTrue(vm.uiState.value is UiState.Loaded)

        // Появился спуфер на том же UUID без валидного кода — предупреждение вытесняет карточку.
        scanner.emit(BeaconInfo(uuid, rssi = -40, authData = null))
        advanceUntilIdle()
        assertTrue(vm.uiState.value is UiState.Untrusted)
    }
}

/** Фейковый сканер: тест сам толкает [detectedBeacon]. Остальное — заглушки. */
private class FakeBleScanner : BleScanner {
    private val _detected = MutableStateFlow<BeaconInfo?>(null)
    override val detectedBeacon: StateFlow<BeaconInfo?> = _detected.asStateFlow()
    override val visibleBeacons: StateFlow<List<BeaconInfo>> = MutableStateFlow(emptyList())
    override val scanState: StateFlow<ScanState> = MutableStateFlow(ScanState.Scanning)

    fun emit(beacon: BeaconInfo?) { _detected.value = beacon }

    override fun startScan(allowedUuids: Set<String>) {}
    override fun stopScan() {}
    override fun release() {}
}

/** Фейковый репозиторий: сразу отдаёт готовый список. */
private class FakeLandmarkRepository(state: LandmarkLoad) : LandmarkRepository {
    override val landmarks: StateFlow<LandmarkLoad> = MutableStateFlow(state)
    override suspend fun refresh() {}
}
