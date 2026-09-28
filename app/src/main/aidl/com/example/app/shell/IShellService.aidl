package com.example.app.shell;

// Сервис, который Shizuku запускает от имени shell (uid 2000) — как «adb shell».
interface IShellService {
    // Зарезервированный Shizuku код транзакции: остановка сервиса.
    void destroy() = 16777114;

    // Выполняет команду через `sh -c`. Возвращает "<код возврата>\n<stdout+stderr>".
    String exec(String command) = 1;
}
