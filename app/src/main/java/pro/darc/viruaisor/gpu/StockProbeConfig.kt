package pro.darc.viruaisor.gpu

internal object StockProbeConfig {
    val kernelParams = listOf("panic=0", "console=ttyS0,115200", "earlycon=uart8250,mmio,0x3f8", "8250.nr_uarts=4")
}
