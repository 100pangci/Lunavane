package dev.nova.zerotier.vpn

import org.json.JSONObject

internal data class AddressConfig(val address: String, val prefix: Int)
internal data class RouteConfig(val address: String, val prefix: Int, val gateway: String? = null)
internal data class MulticastConfig(val mac: Long, val adi: Long)

internal data class NetworkConfig(
    val networkId: Long,
    val status: Int,
    val name: String,
    val mac: Long,
    val mtu: Int,
    val addresses: List<AddressConfig>,
    val routes: List<RouteConfig>,
    val dnsServers: List<String>,
    val dnsDomain: String,
    val multicast: List<MulticastConfig>,
) {
    companion object {
        fun parse(json: String): NetworkConfig {
            val root = JSONObject(json)
            return NetworkConfig(
                networkId = root.getString("networkId").toULong(16).toLong(),
                status = root.getInt("status"),
                name = root.optString("name"),
                mac = root.getString("mac").toULong(16).toLong(),
                mtu = root.optInt("mtu", 2800).coerceIn(576, 9000),
                addresses = root.getJSONArray("addresses").let { values ->
                    (0 until values.length()).map { index ->
                        val item = values.getJSONObject(index)
                        AddressConfig(item.getString("address"), item.getInt("prefix"))
                    }
                },
                routes = root.getJSONArray("routes").let { values ->
                    (0 until values.length()).map { index ->
                        val item = values.getJSONObject(index)
                        RouteConfig(item.getString("address"), item.getInt("prefix"), item.optString("gateway").takeIf(String::isNotBlank))
                    }
                },
                dnsServers = root.getJSONArray("dnsServers").let { values ->
                    (0 until values.length()).map(values::getString)
                },
                dnsDomain = root.optString("dnsDomain"),
                multicast = root.getJSONArray("multicast").let { values ->
                    (0 until values.length()).map { index ->
                        val item = values.getJSONObject(index)
                        MulticastConfig(item.getString("mac").toULong(16).toLong(), item.getLong("adi"))
                    }
                },
            )
        }
    }
}
