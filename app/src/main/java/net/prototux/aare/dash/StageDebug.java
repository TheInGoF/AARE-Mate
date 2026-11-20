package net.prototux.aare.dash;

/** Debug tools' way into the dashboard stage (package-private otherwise). */
public final class StageDebug {
    private StageDebug() {}

    /** The page's last answer to [js]. */
    public static volatile String last;

    public static boolean crashRenderer() { return CarStage.debugCrashRenderer(); }

    public static boolean js(String code, android.webkit.ValueCallback<String> answer) { return CarStage.debugJs(code, answer); }
}
