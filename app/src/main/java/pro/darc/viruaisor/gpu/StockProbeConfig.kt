package pro.darc.viruaisor.gpu

internal object StockProbeConfig {
    val kernelParams = listOf("panic=0", "console=ttyS0,115200", "earlycon=uart8250,mmio,0x3f8", "8250.nr_uarts=4")
    fun validFault(value: String) = value in setOf("none", "owner-death", "lease-timeout")
    fun recoveryToken(value: String): String? = Regex("stock-probe-v1:([0-9a-f]{32})\\n").matchEntire(value)?.groupValues?.get(1)
}
