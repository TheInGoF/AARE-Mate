package net.prototux.aare.ui.car.navi

/**
 * The charging networks the driver can prefer (O205: "betreiber auswahl"; decision 16.11.2026: preferred, others only
 * where none of them is in reach). The operators in the shipped charger list are written in many ways ("EnBW mobility+
 * AG und Co.KG", "EnBW"), so each network is a pattern over the operator's name. Ordered by how many fast chargers of
 * Germany and Denmark they run (assets/navi/schnelllader.tsv, 16.11.2026).
 */
internal object ChargeNetworks {
    class Network(val id: String, val name: String, private val pattern: Regex) {
        fun runs(operator: String) = pattern.containsMatchIn(operator)
    }

    private fun n(id: String, name: String, rx: String) = Network(id, name, Regex(rx, RegexOption.IGNORE_CASE))

    val ALL = listOf(
        n("enbw", "EnBW", "enbw"), n("shell", "Shell Recharge", "shell"), n("ewe", "EWE Go", "ewe go"),
        n("aral", "Aral pulse", "\\baral\\b|bp europa"), n("allego", "Allego", "allego"), n("pfalzwerke", "Pfalzwerke", "pfalzwerke"),
        n("vattenfall", "Vattenfall InCharge", "vattenfall|incharge"), n("aldi", "ALDI", "aldi"), n("tesla", "Tesla", "tesla"),
        n("ionity", "IONITY", "ionity"), n("eon", "E.ON Drive", "e\\.on"), n("circlek", "Circle K", "circle k"), n("mer", "Mer", "^mer\\b"),
        n("total", "TotalEnergies", "total"), n("clever", "Clever", "clever"), n("comfortcharge", "Comfortcharge", "comfortcharge"),
        n("teag", "TEAG Mobil", "teag"), n("citywatt", "Citywatt", "citywatt"), n("norlys", "Norlys", "norlys"), n("ok", "OK", "^ok\\b"),
        n("fastned", "Fastned", "fastned"), n("jolt", "Jolt", "jolt"), n("electra", "Electra", "electra"), n("jet", "JET", "^jet\\b"),
        n("enercity", "enercity", "enercity"), n("lidl", "Lidl / Kaufland", "lidl|kaufland|schwarz"),
    )

    /** The network that runs a charger of [operator], null for the many small ones. */
    /** The name to show for a network id, e.g. "TotalEnergies" for "total". */
    fun name(id: String?): String? = id?.let { i -> ALL.firstOrNull { it.id == i }?.name }

    fun of(operator: String): String? = if (operator.isBlank()) null else ALL.firstOrNull { it.runs(operator) }?.id
}
