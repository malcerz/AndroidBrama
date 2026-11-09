# Raport z Rozbudowy Projektu AutoBrama: 3 Niezależne Sloty, Mapa OpenStreetMap oraz Tryb Testowy Bez Samochodu

**Data:** 2026-11-09  
**Środowisko:** Android Studio, Gradle 9.5.0, AGP 9.3.1, Kotlin 2.3.20, osmdroid 6.1.18, compileSdk 36, targetSdk 36  
**Urządzenie testowe:** Emulator Android Studio (`Pixel_10_Pro_XL`, Android 37.0 / x86_64)  
**Lokalizacja projektu:** `c:\_DEV\Android\AutoBrama\`  

---

## 1. Zakres Rozbudowy

Zgodnie z wymaganiami projekt **AutoBrama** został rozbudowany o:
1. **Model danych dla 3 niezależnych lokalizacji (slotów):**
   - Klasa `GateLocationSlot` z polami: `id` (1, 2, 3), `name` (edytowalna nazwa), `enabled` (aktywność slotu), `phoneNumber` (dedykowany numer telefonu), `latitude`, `longitude`, `radiusMeters` (indywidualny promień strefy 20m–500m).
   - Serializacja i deserializacja JSON w `PreferencesManager` (DataStore) z zachowaniem pełnej wstecznej kompatybilności i automatyczną migracją dotychczasowych ustawień do Slotu 1.
2. **Interaktywny wybór punktu na mapie (OpenStreetMap / `osmdroid` bez klucza API):**
   - Dodanie zależności `org.osmdroid:osmdroid-android:6.1.18` oraz uprawnień sieciowych `INTERNET` i `ACCESS_NETWORK_STATE`.
   - Komponent `MapPickerDialog` z mapą `MapView`:
     - Automatyczne centrowanie na bieżącej pozycji GPS z dużym zoomem (zoom 18.0).
     - Przycisk pływający "Wycentruj na mojej pozycji GPS".
     - Wskazywanie punktu palcem na mapie przez `MapEventsOverlay` (`singleTapConfirmedHelper`).
     - Renderowanie markera oraz okręgu strefy aktywacji (`Polygon.pointsAsCircle`) o zadanym promieniu.
     - Suwak do bezpośredniej regulacji promienia na mapie oraz zatwierdzenie punktu wpisujące współrzędne i promień do edytowanego slotu.
3. **Niezależna obsługa wielu slotów w `AutoCallForegroundService`:**
   - Serwis w pętli odczytu GPS (co 3 sekundy) sprawdza wszystkie włączone sloty (`enabled == true` i poprawny numer telefonu).
   - Każdy slot posiada własną, dedykowaną instancję `GateTriggerStateMachine` z indywidualnym promieniem oraz histerezą wyjścia `radiusMeters + 150f`.
   - Wjazd w strefę danego slotu wyzwala połączenie na dedykowany dla niego numer telefonu.
   - Ciągłe powiadomienie Foreground oraz karta w UI prezentują odległość do **najbliższej** aktywnej bramy (np. `"Najbliżej: Brama 1 (Dom) (1 m)"`).
4. **Interfejs użytkownika w Jetpack Compose (Material 3):**
   - Przebudowa sekcji konfiguracji na 3 zakładki `TabRow` (Slot 1, Slot 2, Slot 3) ze wskaźnikami aktywności (zielona kropka dla włączonych slotów).
   - W każdej zakładce: przełącznik aktywności slotu, pole edycji nazwy, numeru telefonu z przyciskiem testu połączenia, koordynaty z przyciskiem wywołania mapy OSM oraz suwak promienia (20m–500m).
5. **Przełącznik weryfikacji Android Auto (Tryb testowy bez auta):**
   - Nowy parametr w DataStore: `require_android_auto` (domyślnie `true`).
   - Przełącznik na karcie głównej UI: "Wymagaj Android Auto" z dynamicznym podtytułem ("GPS włącza się tylko w aucie" / "Tryb testowy (bez auta) – GPS aktywny od razu").
   - Po wyłączeniu przełącznika aplikacja przechodzi w "Tryb testowy (bez auta)": `AutoCallForegroundService` uruchamia się natychmiast bez konieczności połączenia z samochodem (`CarConnection`).
   - Po włączeniu z powrotem aplikacja automatycznie zatrzymuje usługę, jeśli telefon nie jest podłączony do Android Auto.
   - Odznaka statusu na żywo informuje o trybie (`"NIEWYMAGANE (Bez auta)"` vs `"POŁĄCZONO"` / `"ROZŁĄCZONO"`).
   - Treść powiadomienia Foreground Service uwzględnia aktywny tryb (prefix: `AutoBrama (tryb bez auta) – ...`).

---

## 2. Architektura Rozwiązania

```
com.autobrama.app
├── connection
│   └── CarConnectionManager.kt      # StateFlow stanu projekcji Android Auto
├── data
│   ├── GateLocationSlot.kt          # Model 3 slotów z serializacją/deserializacją JSON
│   └── PreferencesManager.kt        # DataStore Preferences (slotsFlow, requireAndroidAutoFlow)
├── receiver
│   └── CarConnectionReceiver.kt     # BroadcastReceiver zdarzeń CarConnection
├── service
│   ├── ActiveGateStatus.kt          # Obiekt stanu bramy (slot, distance, zoneState)
│   └── AutoCallForegroundService.kt # Map<Int, GateTriggerStateMachine>, requireAA logic
├── statemachine
│   └── GateTriggerStateMachine.kt   # Maszyna stanów z histerezą (+150m)
└── ui
    ├── MainActivity.kt              # TabRow (Slot 1, 2, 3), przełącznik AA, stan na żywo
    ├── MainViewModel.kt             # Reaktywna integracja slotów, trybu AA i akcji
    └── MapPickerDialog.kt           # Pełnoekranowy dialog wyboru punktu z osmdroid (MapView)
```

---

## 3. Wyniki Testów Jednostkowych

W pliku `app/src/test/java/com/autobrama/app/data/GateLocationSlotTest.kt` zaimplementowano testy:
- `testDefaultSlotsContainThreeConfiguredSlots` – poprawność 3 domyślnych slotów.
- `testJsonSerializationAndDeserialization` – bezbłędny zapis i odczyt listy 3 slotów z JSON.
- `testIndependentStateMachinesForMultipleSlots` – weryfikacja niezależnego działania maszyn stanów (wjazd w strefę Slotu 1 wyzwala połączenie tylko dla Slotu 1, podczas gdy Slot 2 pozostaje w stanie czuwania, a następnie po przemieszczeniu wyzwala się Slot 2).

**Wynik uruchomienia `gradlew test`:**
```
> Task :app:testDebugUnitTest
BUILD SUCCESSFUL in 24s
```
Wszystkie testy jednostkowe zakończyły się wynikiem **100% SUKCES**.

---

## 4. Weryfikacja na Emulatorze Android Studio (`Pixel_10_Pro_XL`)

### 4.1. Interaktywna mapa OpenStreetMap (`MapPickerDialog`)
1. Po kliknięciu w "Wybierz punkt na mapie (OSM)" aplikacja otworzyła pełny widok mapy wycentrowany na GPS z zoomem 18.0.
2. Pobrano kafelki z serwerów OSM (TileSource `MAPNIK`).
3. Wskazano nowy punkt na mapie (tapnięcie na współrzędne `52.23102, 21.01181`).
4. Marker i okrąg strefy (`Polygon.pointsAsCircle`) przesunęły się w wybrane miejsce.
5. Kliknięcie "Zatwierdź punkt" zaktualizowało współrzędne w DataStore dla edytowanego slotu.

### 4.2. Konfiguracja dwóch aktywnych slotów
- **Slot 1 (Brama Dom):** Lat: `52.23102`, Lon: `21.01181`, Promień: `50m`, Telefon: `+48123456789`, `enabled = true`.
- **Slot 2 (Brama Firma):** Lat: `52.23500`, Lon: `21.02000`, Promień: `50m`, Telefon: `+48987654321`, `enabled = true`.

### 4.3. Test wjazdu pod Bramę 2 (Firma)
Wysłano pozycję GPS: `52.23510, 21.02000` (dystans ~11m od Slotu 2, ~700m od Slotu 1):
```
I AutoCallFgService: Wjazd w strefę slotu Brama 2 (Firma)! Dystans: 11 m, promień: 50.0 m
I AutoCallFgService: Wywołano Intent.ACTION_CALL pod numer: +48987654321 (Brama 2 (Firma))
```
Aplikacja poprawnie rozpoznała wjazd pod Bramę 2 i wykonała połączenie na dedykowany numer `+48987654321`.

### 4.4. Test przemieszczenia i wjazdu pod Bramę 1 (Dom)
Wysłano pozycję GPS: `52.23100, 21.01181` (dystans ~1m od Slotu 1, ~700m od Slotu 2):
```
I AutoCallFgService: Wjazd w strefę slotu Brama 1 (Dom)! Dystans: 1 m, promień: 50.0 m
I AutoCallFgService: Wywołano Intent.ACTION_CALL pod numer: +48123456789 (Brama 1 (Dom))
```
Aplikacja poprawnie wywołała numer Bramy 1 `+48123456789`.

### 4.5. Test Trybu Testowego Bez Samochodu (Przełącznik "Wymagaj Android Auto")
1. **Wyłączenie przełącznika w UI:**
   - Przełącznik "Wymagaj Android Auto" ustawiono na `false`.
   - Usługa `AutoCallForegroundService` uruchomiła się natychmiast bez konieczności połączenia z samochodem:
     ```
     D AutoCallFgService: onCreate: Tworzenie usługi AutoCallForegroundService (3 sloty)
     I AutoCallFgService: Rozpoczęto odczyt lokalizacji co 3s dla aktywnych slotów bram
     D AutoCallFgService: Stan CarConnection w usłudze: 0 (wymagane AA: false)
     ```
   - Karta statusu w UI wyświetliła stan Android Auto jako `"NIEWYMAGANE (Bez auta)"`, a usługę jako `"DZIAŁA (GPS 3s)"`.
   - Ciągłe powiadomienie wyświetliło treść:
     ```
     android.title=String (AutoBrama)
     android.text=String (AutoBrama (tryb bez auta) – najbliżej: Brama 1 (Dom) (1 m))
     ```
2. **Weryfikacja histerezy i ponownego wyzwalania połączenia:**
   - Symulacja oddalenia: wysłano GPS `52.23500, 21.01181` (odległość 443 m > 50 m + 150 m histerezy).
   - Powiadomienie zaktualizowało się na:
     ```
     android.text=String (AutoBrama (tryb bez auta) – najbliżej: Brama 1 (Dom) (443 m))
     ```
   - Stan maszyny powrócił do `OUTSIDE_ZONE`.
   - Symulacja ponownego zbliżenia pod bramę: wysłano GPS `52.23102, 21.01181` (odległość 0 m):
     ```
     I AutoCallFgService: Wjazd w strefę slotu Brama 1 (Dom)! Dystans: 0 m, promień: 50.0 m
     I AutoCallFgService: Wywołano Intent.ACTION_CALL pod numer: +48123456789 (Brama 1 (Dom))
     ```
   - Połączenie telefoniczne zostało wykonane natychmiast po powrocie w strefę.
3. **Ponowne włączenie wymagania Android Auto:**
   - Przestawienie przełącznika na `true` przy braku aktywnego połączenia AA natychmiast zatrzymało usługę w tle:
     ```
     D AutoCallFgService: Stan CarConnection w usłudze: 0 (wymagane AA: true)
     I AutoCallFgService: Android Auto rozłączone i wymagane – zatrzymywanie usługi AutoCallForegroundService
     D AutoCallFgService: onDestroy: AutoCallForegroundService zatrzymany
     ```
   - Polecenie `dumpsys activity services AutoCallForegroundService` potwierdziło całkowite wyczyszczenie procesu usługi.

---

## 5. Zrzuty Ekranu w Katalogu Raportów

- `c:\_DEV\Android\AutoBrama\Raporty\screenshot_3slots.png` – ekran główny z zakładkami 3 slotów.
- `c:\_DEV\Android\AutoBrama\Raporty\screenshot_map_dialog.png` – interaktywny dialog wyboru punktu na mapie OpenStreetMap z okręgiem promienia.
- `c:\_DEV\Android\AutoBrama\Raporty\screenshot_confirmed.png` – stan po zatwierdzeniu nowego punktu z mapy.
- `c:\_DEV\Android\AutoBrama\Raporty\screenshot_slot2_saved.png` – konfiguracja i aktywacja drugiego slotu.
- `c:\_DEV\Android\AutoBrama\Raporty\screenshot_multislot_live.png` – karta stanu na żywo z prezentacją odległości do najbliższej bramy.
- `c:\_DEV\Android\AutoBrama\Raporty\screenshot_no_car_mode.png` – widok aplikacji z wyłączonym wymogiem Android Auto (tryb testowy bez auta, usługa aktywna i monitorująca GPS).
