# Symulator Giełdy (Stock Exchange)

Projekt symulujący działanie rozproszonej giełdy akcji. System obsługuje portfele użytkowników, inwentarz banku oraz logowanie transakcji kupna/sprzedaży.


Szczegółowy opis decyzji architektonicznych znajduje się w osobnym pliku: [ARCHITECTURE.md](ARCHITECTURE.md).

### Architektura i Działanie Systemu

System składa się z trzech węzłów (backendów) oraz Load Balancera, napisanych w czystej Javie. 
Ruch HTTP obsługiwany jest przez wbudowany serwer `com.sun.net.httpserver.HttpServer`.

1. **Load Balancer (L7)**
   Przyjmuje zapytania REST od użytkowników i rozdziela je na dostępne węzły. Aby zminimalizować błędy współbieżności, używa strategii *Modulo-based sticky routing* – zapytania o daną akcję (np. AAPL) zawsze trafiają na ten sam węzeł w stabilnych warunkach sieciowych. Load Balancer posiada aktywny mechanizm sprawdzania kondycji (Health Checks) i dynamicznie przywraca powracające węzły do puli.

2. **Replikacja P2P**
   Gdy jeden z węzłów pomyślnie przetworzy transakcję, asynchronicznie rozgłasza ją do pozostałych węzłów (wzorzec Fire-and-Forget). Węzły odbierające wymuszają aktualizację lokalnego stanu bez ponownej walidacji warunków biznesowych.

3. **Zarządzanie stanem**
   Stan utrzymywany jest w pamięci (In-Memory). Dostęp do zasobów zabezpieczony jest metodą *Lock-striping* (osobne zamki `ReentrantLock` dla każdej z akcji). Pozwala to na pełną współbieżność operacji, o ile nie dotyczą one tego samego waloru. Przed podwójnym przetworzeniem żądań (idempotentność) chroni bezblokujący LRU Cache w postaci `ConcurrentHashMap` działający w parze z kolejką.

### Zalety

* **Wydajność** – Serwer, klient HTTP oraz sprawdzanie kondycji węzłów działają na lekkich Wątkach Wirtualnych (Virtual Threads).
* **Odporność na awarie (High Availability)** – Aplikacja radzi sobie z nagłym padem węzła (symulowanym przez endpoint `/chaos`). Load Balancer omija uszkodzone instancje bez zrywania połączeń klientów.
* **Automatyczny Bootstrapping** – Po restarcie lub nagłej awarii węzła, uszkodzona instancja natychmiast asynchronicznie pobiera pełny stan (zrzut In-Memory) od dostępnych peerów przed powrotem do puli Load Balancera. Rozwiązuje to problem trwałej korupcji danych po utracie pamięci.
* **Wysoka współbieżność** – Mechanizm Lock-striping eliminuje wąskie gardła synchronizacji i zapobiega blokowaniu całego rynku przy intensywnym ruchu.

### Wady / Ograniczenia

* **Brak stuprocentowej spójności (Write Skew)** – W przypadku awarii jednego z węzłów, podczas opóźnień replikacji lub rebalansowania ruchu, może dojść do zjawiska Write Skew i rozjazdu stanów między instancjami. System nie realizuje twardego konsensusu rozproszonego (np. algorytmu Raft).
* **Stan w pamięci RAM** – Cały stan giełdy jest ulotny. Całkowity restart klastra skutkuje nieodwracalną utratą danych.

### Jak uruchomić

Projekt jest w pełni skonteneryzowany i wykorzystuje Docker Compose.

**Linux / macOS (ARM64 & x64)**
```bash
./start.sh 8080
```

**Windows (x64)**
```cmd
start.bat 8080
```

Skrypt automatycznie buduje aplikację za pomocą Gradle, podnosi 3 węzły backendowe i uruchamia Load Balancer na wybranym porcie.

Można przetestować odporność klastra na awarie symulując zabicie jednego z węzłów poleceniem:
`curl -X POST http://localhost:8080/chaos`
