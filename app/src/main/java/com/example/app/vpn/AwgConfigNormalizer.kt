package com.example.app.vpn

/**
 * Приводит .conf к виду, который корректно работает и в Wi-Fi, и в мобильной сети:
 *  - AllowedIPs: весь IPv4 (кроме приватных диапазонов) + ::/0, чтобы IPv6 не утекал мимо туннеля;
 *  - DNS и MTU подставляются, только если их нет в конфиге.
 */
object AwgConfigNormalizer {
    private const val ALLOWED_IPS =
        "0.0.0.0/5, 8.0.0.0/7, 11.0.0.0/8, 12.0.0.0/6, 16.0.0.0/4, 32.0.0.0/3, " +
        "64.0.0.0/2, 128.0.0.0/3, 160.0.0.0/5, 168.0.0.0/6, 172.0.0.0/12, " +
        "172.32.0.0/11, 172.64.0.0/10, 172.128.0.0/9, 173.0.0.0/8, 174.0.0.0/7, " +
        "176.0.0.0/4, 192.0.0.0/9, 192.128.0.0/11, 192.160.0.0/13, 192.169.0.0/16, " +
        "192.170.0.0/15, 192.172.0.0/14, 192.176.0.0/12, 192.192.0.0/10, " +
        "193.0.0.0/8, 194.0.0.0/7, 196.0.0.0/6, 200.0.0.0/5, 208.0.0.0/4, ::/0"

    private val dnsRe = Regex("^\\s*DNS\\s*=", RegexOption.IGNORE_CASE)
    private val mtuRe = Regex("^\\s*MTU\\s*=", RegexOption.IGNORE_CASE)

    fun normalize(text: String): String {
        val src = text.lines()
        val hasDns = src.any { dnsRe.containsMatchIn(it) }
        val hasMtu = src.any { mtuRe.containsMatchIn(it) }
        val out = ArrayList<String>(src.size + 2)
        for (line in src) {
            val t = line.trim()
            when {
                t.equals("[Interface]", ignoreCase = true) -> {
                    out += line
                    if (!hasDns) out += "DNS = 1.1.1.1, 1.0.0.1"
                    if (!hasMtu) out += "MTU = 1280"
                }
                t.startsWith("AllowedIPs", ignoreCase = true) ->
                    out += "AllowedIPs = $ALLOWED_IPS"
                else -> out += line
            }
        }
        return out.joinToString("\n")
    }
}
