# BLE-Гид: Курск 1000

Интерактивный мобильный гид к 1000-летию Курска. Приложение сканирует пространство и при
обнаружении физической BLE-метки рядом с достопримечательностью автоматически открывает
карточку с историей, фактами и медиа — без ручного поиска.

Проект делался под профиль информационной безопасности, поэтому помимо базового гида в нём
реализован **трек защиты меток от подмены и клонирования** (spoofing / replay) — динамические
TOTP-подобные коды на HMAC-SHA256 с ключами в Android Keystore.


---

## Что входит в проект

Система состоит из трёх частей. Первые две собираются из **этого** репозитория в один APK
(две иконки-launcher), третья — отдельный backend.

| Часть | Где живёт | Назначение |
|-------|-----------|------------|
| **Гид** (`MainActivity`) | этот репозиторий | Сканирует BLE, проверяет подлинность метки, показывает карточку достопримечательности |
| **Эмулятор метки** (`BeaconEmulatorActivity`) | этот репозиторий | Программная замена аппаратного маячка (ESP32): вещает подлинный или поддельный сигнал. Для демо «атака vs защита» |
| **Backend** (FastAPI) | `../kursk1000-api` | Отдаёт контент достопримечательностей и секреты меток. Источник правды |

> Эмулятор объявлен отдельной `<activity>` с `LAUNCHER`-intent-filter в одном APK — после
> установки на устройстве появляются **две иконки**: «Kursk1000» (гид) и «Эмулятор метки».

---

## Стек

- **Язык / UI:** Kotlin, Jetpack Compose (Material 3), edge-to-edge.
- **Архитектура:** MVVM, ручной DI через `AppContainer`, repository-pattern, offline-first.
- **Асинхронность:** Kotlin Coroutines + `StateFlow`.
- **Кэш:** Room (offline-first), пережив­ает перезапуск.
- **Медиа:** Coil 3 (изображения), Media3 / ExoPlayer (видео).
- **Сеть:** `HttpURLConnection` (без сторонних HTTP-клиентов).
- **Безопасность:** `javax.crypto` (HMAC-SHA256) + Android Keystore (неэкспортируемые ключи).
- **minSdk 24 / targetSdk 37**, AGP 9.2, Kotlin 2.4, Compose BOM 2026.06.

Версии зафиксированы в [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

---

## Архитектура

```
            ┌──────────────────────── UI (Compose) ────────────────────────┐
            │  MainActivity / BleScreen          BeaconEmulatorActivity     │
            └───────────────┬───────────────────────────┬──────────────────┘
                            │                            │
                  LandmarkViewModel            BeaconEmulatorViewModel
                            │                            │
        ┌───────────────────┼──────────────┐            │
        │                   │              │            │
   BleScanner      LandmarkRepository  BeaconVerifier  BeaconAdvertiser
   (RealBle…)      (OfflineFirst…)          │            │
        │           │            │          └────┬───────┘
        │       Room (DAO)  RemoteLandmark…       │
        │           │            │        BeaconAuthKeyProvider
        │           │            │        (Android Keystore)
        │           ▼            ▼                ▲
        │      kursk.db    GET /landmarks ────────┘  beacon_secret → Keystore
        ▼
   BLE radio  ◄────────  эфир  ────────►  BLE radio (эмулятор / ESP32)
```

Сборка зависимостей — вручную в [`AppContainer`](app/src/main/java/com/kursk1000/AppContainer.kt),
создаётся один раз в `Kursk1000App` (наследник `Application`). ViewModel’и получают
зависимости через `viewModelFactory` — это позволяет подменять их фейками в тестах.

### Слой данных (offline-first)

`LandmarkRepository` — шов между ViewModel и источниками данных. Боевая реализация
`OfflineFirstLandmarkRepository`:

1. UI всегда читает из **Room** (`dao.observeAll()`), поэтому контент доступен офлайн сразу
   после первой синхронизации.
2. `refresh()` ходит на backend (`GET /landmarks`), и при успехе перезаписывает кэш.
3. Деградация без падений: пустой ответ сервера **не** стирает рабочий кэш; ошибка сети при
   наличии кэша показывается баннером «Нет связи с сервером — показаны сохранённые данные»
   (`LandmarkLoad.Ready.refreshError`), а не пустым экраном.

Состояния списка описаны типом `LandmarkLoad` (`Loading` / `Ready` / `Failed`). JSON с сервера
парсится устойчиво: битые поля → пустые значения, битые элементы массивов пропускаются.

### Жизненный цикл сканирования

- Скан привязан к жизненному циклу **процесса** (`ProcessForegroundMonitor` поверх
  `ProcessLifecycleOwner`), а не Activity. Поэтому поворот экрана не дёргает Bluetooth.
  Activity не пересоздаётся при повороте (`configChanges` в манифесте).
- Скан запускается только когда выполнены **все** условия: приложение на переднем плане +
  выдано разрешение + белый список UUID непуст.
- Карточка «залипает»: открытая карточка держится, даже если метка пропала из эфира —
  закрывает только пользователь (с паузой 3 с, чтобы она не открылась тут же снова).

---

## Архитектура защиты 

**Проблема.** Стандартный iBeacon вещает статический UUID в открытом виде. Злоумышленник
перехватывает UUID у памятника и запускает его эмулятор в другом месте (spoofing / relay),
ломая логику гида.

**Решение — динамические идентификаторы (TOTP-подобные).** Метка вещает не статический
идентификатор, а **код, меняющийся каждые 30 секунд**:

```
counter = epochMillis / 30000                         # шаг времени
code    = HMAC-SHA256(secret, counter)[:8]            # усечён до 8 байт
payload = [VERSION=0x01] + code                        # 9 байт в BLE Service Data
```

Гид независимо считает тот же HMAC и сравнивает за константное время. Реализация — общий
модуль [`BeaconCode`](app/src/main/java/com/kursk1000/BeaconCode.kt) (без Android-зависимостей,
используется и гидом, и эмулятором), проверка — [`BeaconVerifier`](app/src/main/java/com/kursk1000/BeaconVerifier.kt).

Что это даёт:

- **Защита от клонирования.** Без секрета будущие коды вычислить нельзя — статический UUID
  бесполезен.
- **Защита от replay.** Код протухает за ~30 с (окно `±1` шаг для терпимости к дрейфу часов).
  Дополнительно `BeaconVerifier` хранит последний принятый счётчик по каждому UUID и
  отклоняет строго устаревшие — переигранный пакет не пройдёт. Карта счётчиков живёт весь
  сеанс и **не** сбрасывается при закрытии карточки (иначе открылось бы окно для повтора).

### Где лежат ключи

Требование ТЗ — **не хранить секреты в коде**. Поток ключа:

```
seed.py (backend) ──HMAC-секрет──► GET /landmarks ──► клиент ──► Android Keystore
   (32 байта)                                              (неэкспортируемый ключ)
        │
        └──► тот же секрет прошивается в ESP32-метку
```

- [`KeystoreBeaconAuthKeyProvider`](app/src/main/java/com/kursk1000/BeaconAuthKeyProvider.kt)
  импортирует секрет в **Android Keystore** как неэкспортируемый HMAC-ключ. Сам HMAC считает
  Keystore — сырой ключ недоступен наружу даже при компрометации приложения.
- Секрет **не попадает в Room** (`secrets` идут в Keystore отдельно от контента) — в кэше
  сырья ключа нет.
- Ключ переживает перезапуск, поэтому после первой синхронизации проверка работает **офлайн**.
- В исходниках клиента секретов нет вообще.

> Схема симметричная (один и тот же секрет у метки и у гида), поэтому секрет уезжает на
> клиент. В проде раздавайте `/landmarks` **по HTTPS** (reverse-proxy с TLS перед uvicorn);
> по демо-LAN допустим HTTP.

### Реакция гида на поддельную метку

Ноль реакции. Сканер ключует устройства по **MAC** (реальная и поддельная метка на одном
UUID — две разные записи), а `LandmarkViewModel` пропускает дальше только метки со статусом
`AUTHENTIC`. Поддельная метка не появляется ни на радаре, ни в карточке — отдельного UI
«недоверенная метка» нет намеренно.

### Демо «атака vs защита»

В эмуляторе есть переключатель **«Защищённая» / «Уязвимая»**:

| Режим | Что вещает | Что видит гид |
|-------|-----------|---------------|
| Защищённая | UUID + ротирующийся код (каждые 30 с) | Открывает карточку, метит «✓ Подлинная метка» |
| Уязвимая | только UUID, без кода (клон / спуфер) | Обнаруживает, но **не доверяет** — карточка не открывается |

---

## Сканер и эмулятор: детали BLE

**Сканер** ([`BleScanner`](app/src/main/java/com/kursk1000/BleScanner.kt)):

- Hardware-фильтры `ScanFilter` по белому списку UUID (приходит из кэша достопримечательностей).
- Два порога RSSI: `MIN_RSSI = -75` (пускает метку на радар) и `NEAR_RSSI = -60` (строже —
  открытие карточки, ~2–5 м на TxPower HIGH).
- Метка считается пропавшей через 3 с без сигнала (sweep раз в секунду).
- `SCAN_MODE_BALANCED` (посетитель подходит пешком — батарею незачем жечь), кулдаун 5с между
  стартами (Android 7+ режет частые перезапуски скана).
- Реагирует на включение/выключение Bluetooth (`BroadcastReceiver` на `ACTION_STATE_CHANGED`)
  и автоматически возобновляет скан.

**Эмулятор** ([`BeaconAdvertiser`](app/src/main/java/com/kursk1000/BeaconAdvertiser.kt) +
[`BeaconEmulatorViewModel`](app/src/main/java/com/kursk1000/BeaconEmulatorViewModel.kt)):

- `BluetoothLeAdvertiser`, UUID — в основном пакете, код подлинности — в Service Data через
  scan response (так умещается 128-битный UUID + payload в бюджет 31 байта).
- В защищённом режиме корутина переиздаёт пакет на каждой смене счётчика (раз в 30 с).
- Настройки в UI: выбор UUID (из того же кэша, что у гида), режим, мощность передатчика
  (`TxPower` Low/Medium/High), Старт/Стоп.

---

## Разрешения

Корректно разведены по версиям ОС (см. [`AndroidManifest.xml`](app/src/main/AndroidManifest.xml)):

| Разрешение | Версия | Зачем |
|------------|--------|-------|
| `BLUETOOTH_SCAN` (`neverForLocation`) | Android 12+ | Скан без геолокации |
| `BLUETOOTH_ADVERTISE` | Android 12+ | Вещание (эмулятор) |
| `BLUETOOTH` / `BLUETOOTH_ADMIN` | ≤ Android 11 | Скан/вещание на старых ОС |
| `ACCESS_FINE/COARSE_LOCATION` | ≤ Android 11 | BLE-скан до Android 12 требует геолокацию |
| `INTERNET` | все | Синхронизация контента |

Runtime-запрос разрешений — через Accompanist Permissions.

---

## Сборка и запуск

### Требования

- Android Studio (актуальная версия с поддержкой AGP 9.2).
- JDK 11+.
- **Физическое устройство с Bluetooth LE** (эмулятор Android не имеет BLE-радио). Для эмулятора
  метки нужен телефон с поддержкой BLE **peripheral mode** (вещания) — большинство устройств с
  Android 8+.

### 1. Поднять backend

Гид и эмулятор тянут контент и секреты с backend. Сначала запустите его (отдельный репозиторий
`../kursk1000-api`):

```powershell
python -m pip install -r requirements.txt
python seed.py                                            # залить контент в БД
python -m uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Подробности — в README backend.

### 2. Указать адрес backend

Адрес вынесен в `BuildConfig` — поправьте `BASE_URL` под IP вашего ПК в
[`app/build.gradle.kts`](app/build.gradle.kts):

```kotlin
buildConfigField("String", "BASE_URL", "\"http://192.168.0.163:8000\"")
```

Для HTTP к локальному IP уже разрешён cleartext в
[`network_security_config.xml`](app/src/main/res/xml/network_security_config.xml) — впишите туда
свой IP (по умолчанию `192.168.0.163`). Телефон и ПК должны быть в одной сети.

### 3. Собрать APK

### 4. Демо-сценарий

1. Запустить backend, проверить `http://<IP>:8000/landmarks`.
2. На телефоне открыть **гид** — дождаться синхронизации (контент кэшируется в Room).
3. На втором телефоне (или том же) открыть **«Эмулятор метки»**, выбрать UUID.
4. Режим **«Защищённая»** → Старт → у гида открывается карточка с пометкой «✓ Подлинная метка».
5. Переключить на **«Уязвимая»** → гид метку видит, но карточку **не** открывает — спуфинг
   заблокирован.

---

## Структура репозитория

```
app/src/main/java/com/kursk1000/
├── Kursk1000App.kt              Application, держит AppContainer
├── AppContainer.kt             ручной DI: сборка зависимостей
│
├── MainActivity.kt             экран гида (Compose)
├── LandmarkViewModel.kt        состояние гида: скан → проверка → карточка
├── LandmarkCard.kt             UI карточки достопримечательности
├── RadarSearch.kt              UI радара поиска меток
├── VideoPlayback.kt            видеоплеер (Media3)
├── ForegroundMonitor.kt        скан по жизненному циклу процесса
│
├── LandmarkRepository.kt       интерфейс репозитория + типы состояний
├── OfflineFirstLandmarkRepository.kt   offline-first логика
├── RemoteLandmarkDataSource.kt сеть: GET /landmarks + парсинг
├── KurskDatabase.kt            Room: entity, DAO, конвертеры
├── Landmark.kt                 доменная модель
│
├── BeaconCode.kt               TOTP-код метки (общий модуль)
├── BeaconVerifier.kt           проверка кода + анти-replay
├── BeaconAuthKeyProvider.kt    секреты в Android Keystore
│
├── BeaconEmulatorActivity.kt   экран эмулятора (Compose)
├── BeaconEmulatorViewModel.kt  логика вещания
├── BeaconAdvertiser.kt         обёртка BluetoothLeAdvertiser
├── BleScanner.kt               обёртка BluetoothLeScanner
└── ui/theme/                   тема Compose

gradle/libs.versions.toml       version catalog
```

Backend (контент, медиа, секреты, защита со стороны сервера) — в отдельном репозитории
`kursk1000-api` со своим README.
