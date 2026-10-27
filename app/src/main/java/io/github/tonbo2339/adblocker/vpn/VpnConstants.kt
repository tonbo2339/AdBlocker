package io.github.tonbo2339.adblocker.vpn

/** VPN まわりのログの印 (Log・DebugLog の TAG)。 */
internal const val VPN_TAG = "AdBlockVpn"

/** tun の MTU。tun から読む 1 パケットの最大の大きさでもある。 */
internal const val MTU = 16384

/** tun に書ける DNS 応答の最大サイズ (MTU から IPv6 + UDP のヘッダー分を引く)。 */
internal const val MAX_RESPONSE = MTU - 48
