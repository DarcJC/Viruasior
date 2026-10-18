package pro.darc.viruaisor.vm

internal object GuestPasswordProtocol {
    fun command(password: String): String {
        require(password.isNotEmpty() && password.none { it == '\n' || it == '\r' || it == '\u0000' } &&
            Charsets.UTF_8.newEncoder().canEncode(password)) { "密码不能为空或包含换行符" }
        val bytes=password.toByteArray(Charsets.UTF_8)
        try {
            require(bytes.size <= 128) { "密码最多为 128 个 UTF-8 字节" }
            return "SET_DROID_PASSWORD ${java.util.Base64.getEncoder().encodeToString(bytes)}"
        } finally { bytes.fill(0) }
    }
    fun confirm(reply: String?) { check(reply == "OK PASSWORD") { "密码更新失败，请更新配套客体 agent 后重试" } }
}
