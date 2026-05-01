# Architektura Systemu i Decyzje Projektowe

Dokument ten podsumowuje najważniejsze decyzje architektoniczne. Celem było stworzenie rozwiązania prostego, a zarazem odpornego i wydajnego.

### 1. Zapobieganie "Write Skew" (L7 Sharding)
Zamiast skomplikowanych algorytmów konsensusu (np. Raft) czy rozproszonych blokad, zastosowałem pragmatyczne podejście w Warstwie 7 (Load Balancer). 
Aplikacja używa **Modulo-based sticky routing**: zapytania dotyczące konkretnej akcji (np. `AAPL`) są hashowane i zawsze kierowane do tego samego węzła. Gwarantuje to, że w stabilnym klastrze operacje dla danego waloru są przetwarzane sekwencyjnie na jednej maszynie, drastycznie zmniejszając ryzyko nadpisywania stanu (Write Skew).

### 2. Lock-striping zamiast Globalnych Blokad
Stan giełdy i portfeli przechowywany jest In-Memory. Zamiast używać słowa kluczowego `synchronized` lub pojedynczego globalnego zamka na całą klasę `MarketState` (co ubiłoby wydajność przy dużym ruchu), użyłem techniki **Lock-striping**.
Każda akcja posiada własną instancję `ReentrantLock`. Dzięki temu transakcje dotyczące różnych akcji są realizowane w 100% współbieżnie.

### 3. Asynchroniczna Replikacja P2P
Aby zachować układ High Availability (HA), każdy węzeł po lokalnej mutacji stanu rozgłasza ją do pozostałych peerów w klastrze, używając wzorca **Fire-and-Forget**. Odbywa się to całkowicie asynchronicznie, by nie blokować klienta powolną komunikacją sieciową między węzłami. 
Jeżeli dany peer nagle zniknie (awaria), pozostałe nie zawieszają się, a Load Balancer kieruje ruch wyłącznie do zdrowych węzłów.

### 4. Wątki Wirtualne (Virtual Threads)
Cały serwer HTTP, rozgłaszanie zapytań przez `HttpClient` oraz tło sprawdzania zdrowia węzłów bazują na lekkich Wątkach Wirtualnych wprowadzonych w Javie 21. Eliminują one potrzebę konfigurowania puli wątków i świetnie sprawdzają się w operacjach wejścia/wyjścia (I/O).

### 5. Idempotentność bez wycieków pamięci
Dla ochrony przed powtórzonymi przez sieć transakcjami zastosowałem nagłówek `X-Request-ID`. Pamięć o przetworzonych żądaniach oparta jest na wysoce współbieżnym **LRU Cache** (zbudowanym na `ConcurrentHashMap.newKeySet()` i kolejce). Rozwiązanie to jest bezpieczne dla wątków i nie blokuje zbioru podczas odczytów, jednocześnie chroniąc serwer przed błędem "Out of Memory" poprzez automatyczne zrzucanie najstarszych wpisów po przekroczeniu limitu 10 000 żądań.
