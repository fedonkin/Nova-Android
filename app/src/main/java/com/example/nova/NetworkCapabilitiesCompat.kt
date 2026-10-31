package com.example.nova

import android.net.NetworkCapabilities

internal object NetworkCapabilitiesCompat {
    fun getTransportInfo(caps: NetworkCapabilities?): Any? {
        if (caps == null) return null
        return try {
            val method = caps.javaClass.methods
                .firstOrNull { it.name == "getTransportInfo" && it.parameterCount == 0 }
                ?: return null
            method.invoke(caps)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * UID приложения, которое подняло VPN-сеть, или `null`, если прочитать нечем.
     *
     * `getOwnerUid` — скрытый метод `NetworkCapabilities`, и на части прошивок его
     * нет; тогда остаётся разбор `toString()`, где поле печатается как
     * `OwnerUid: 10234`. Ответ нужен, чтобы отличить свой туннель от чужого:
     * Android пропускает только один VPN, и «чужой» — это единственное
     * объяснение, почему наш не поднимается.
     */
    fun getOwnerUid(caps: NetworkCapabilities?): Int? {
        if (caps == null) return null
        val reflected = try {
            caps.javaClass.methods
                .firstOrNull { it.name == "getOwnerUid" && it.parameterCount == 0 }
                ?.invoke(caps) as? Int
        } catch (_: Throwable) {
            null
        }
        if (reflected != null && reflected >= 0) return reflected
        return Regex("(?:OwnerUid|EstablishingAppUid):\\s*(\\d+)")
            .find(caps.toString())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    /**
     * Текстовое описание транспорта VPN: у наших туннелей в нём стоит
     * `NovaVPN`/`NovaOperaVPN`. Пустая строка — описание недоступно, и это
     * отдельный ответ, а не «чужой».
     */
    fun describeTransport(caps: NetworkCapabilities?): String {
        val info = getTransportInfo(caps) ?: return ""
        return try {
            info.toString()
        } catch (_: Throwable) {
            ""
        }
    }
}
