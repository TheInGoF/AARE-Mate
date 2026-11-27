package net.prototux.aare.host

/**
 * Phone apps the driver allowed on the car screen while driving (System L-114, 27.11.2026: "Außerhalb vom Kino-Modus
 * möchte ich die Claude-App nutzen können … nicht nur als native Android-Auto-App, sondern als richtige App zum Bedienen.
 * Aktuell will er, dass ich dafür parke, aber es geht mir nur um die Spracheingaben und die Chats … Es wäre wesentlich
 * sicherer, die App nativ auf dem Display zu haben"; decided by Ingo, Q-069 "A"). In the Apps list whenever the tablet can
 * show phone apps at all (Shizuku), chosen for the cinema or not, and not paused while the car moves. Every other phone
 * app stays parked-only, as the cinema is.
 */
object DrivingApps {
    /** The app of the Claude assistant (voice input and output, chats). */
    const val CLAUDE = "com.anthropic.claude"
    val ALLOWED = setOf(CLAUDE)

    fun allowed(pkg: String) = pkg in ALLOWED
}
