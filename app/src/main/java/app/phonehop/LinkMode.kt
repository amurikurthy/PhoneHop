package app.phonehop

/**
 * The link between phone and router. The router hosts a small hidden Wi-Fi network just for the phone, and the
 * phone joins it like any client (the way a car's wireless Android Auto works). The network has room for exactly
 * one device, so the phone always gets the same address, and DHCP sends no gateway or DNS, so Android keeps using
 * cellular for the phone's own internet.
 */
object Link {
    const val ROUTER_IP = "192.168.51.1"
    const val PHONE_IP = "192.168.51.2"
    const val SUBNET = "192.168.51.0"
    const val PREFIX = 30
}
