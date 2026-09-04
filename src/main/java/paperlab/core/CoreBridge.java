package paperlab.core;

/**
 * Есть ли под плагином наш форк с минимальным патчем ядра.
 *
 * <p>Разделение обязанностей в проекте такое: в ядре живёт <b>только то, чего
 * принципиально нельзя сделать плагином</b> — режим наблюдателя и трасса спавна.
 * Всё остальное (команды, HUD, счётчики, карта чанков, чтение мобкапа) — здесь,
 * потому что плагин пересобирается за секунды, а серверный jar за минуты.
 *
 * <p>Плагин обязан работать и на чистом Paper: варианты A/B/C методики прогоняются
 * на нетронутом сервере, и инструмент не должен мешать этому прогону. Поэтому
 * зависимости от ядра «мягкие»: при отсутствии классов соответствующий модуль
 * переходит в урезанный режим и честно об этом пишет.
 *
 * <p>Ссылки на классы ядра вынесены во вложенные классы-делегаты, которые
 * загружаются только при {@link #PRESENT} — иначе на чистом Paper падало бы
 * разрешение констант при первом обращении.
 */
public final class CoreBridge {

    /** Проверяется один раз при загрузке класса: результат не меняется за время работы. */
    public static final boolean PRESENT = detect();

    private CoreBridge() {
    }

    private static boolean detect() {
        try {
            Class.forName("io.papermc.paper.lab.ghost.LabGhost", false,
                CoreBridge.class.getClassLoader());
            return true;
        } catch (final Throwable ignored) {
            return false;
        }
    }

    /** Строка для лога и для {@code /carpet}: на чём мы сейчас работаем. */
    public static String describe() {
        return PRESENT
            ? "Lab-patched core: observer and spawn trace are complete"
            : "plain Paper: observer is partial, spawn trace has no reason breakdown";
    }
}
