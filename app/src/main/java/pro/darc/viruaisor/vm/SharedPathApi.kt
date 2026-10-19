package pro.darc.viruaisor.vm

internal object SharedPathApi {
    fun supportsApplicationDomain(type: Class<*>): Boolean = type.constructors.any {
        it.parameterTypes.size == 10 && it.parameterTypes[8] == Boolean::class.javaPrimitiveType
    }
    fun create(type: Class<*>, path: String, uid: Int, guestUid: Int, guestGid: Int,
               mask: Int, tag: String, socketName: String, socketPath: String): Any {
        val prefix = arrayOf(String::class.java, Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
            String::class.java, String::class.java)
        val args = arrayOf<Any>(path, uid, uid, guestUid, guestGid, mask, tag, socketName)
        val modern = prefix + arrayOf(Boolean::class.javaPrimitiveType!!, String::class.java)
        val current = prefix + String::class.java
        val constructor = type.constructors.firstOrNull { it.parameterTypes.contentEquals(modern) }
        if (constructor != null) return constructor.newInstance(*args, true, socketPath)
        return type.getConstructor(*current).newInstance(*args, socketPath)
    }
}
