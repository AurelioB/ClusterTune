package com.wuyr.jdwp_injector.debugger

import java.lang.reflect.Method
import kotlin.reflect.KClass

/**
 * @author wuyr
 * @github https://github.com/wuyr/jdwp-injector-for-android
 * @since 2024-01-31 上午10:20
 */
internal val String.isPrimitive: Boolean
    get() = this == "boolean" || this == "byte" || this == "char" || this == "short"
            || this == "int" || this == "long" || this == "float" || this == "double"

internal val Byte.isObject: Boolean
    get() = toInt().run {
        this == REFERENCE_TYPE_OBJECT || this == REFERENCE_TYPE_ARRAY
                || this == REFERENCE_TYPE_CLASS_OBJECT || this == REFERENCE_TYPE_THREAD_GROUP
                || this == REFERENCE_TYPE_CLASS_LOADER || this == REFERENCE_TYPE_STRING
                || this == REFERENCE_TYPE_THREAD
    }

internal val Method.signature: String
    get() = StringBuilder().apply {
        append("(")
        parameterTypes.forEach { append(it.signature) }
        append(")")
        append(returnType.signature)
    }.toString()

internal fun getMethodSignature(vararg paramsTypeName: String, returnTypeName: String = "void") = StringBuilder().apply {
    append("(")
    paramsTypeName.forEach { append(it.signature) }
    append(")")
    append(returnTypeName.signature)
}.toString()

internal val KClass<*>.signature: String get() = java.signature

internal val Class<*>.signature: String get() = name.signature

internal val String.signature: String
    get() = StringBuilder().apply {
        repeat(this@signature.count { c -> c == '[' }) { append('[') }
        when (val signature = this@signature.substring(length)) {
            "boolean" -> append('Z')
            "byte" -> append('B')
            "char" -> append('C')
            "short" -> append('S')
            "int" -> append('I')
            "long" -> append('J')
            "float" -> append('F')
            "double" -> append('D')
            "void" -> append('V')
            "Boolean" -> append("java.lang.Boolean".signature)
            "Byte" -> append("java.lang.Byte".signature)
            "Char" -> append("java.lang.Char".signature)
            "Short" -> append("java.lang.Short".signature)
            "Int" -> append("java.lang.Int".signature)
            "Long" -> append("java.lang.Long".signature)
            "Float" -> append("java.lang.Float".signature)
            "Double" -> append("java.lang.Double".signature)
            "String" -> append("java.lang.String".signature)
            else -> {
                if (isEmpty()) {
                    append("L")
                }
                append(signature.replace('.', '/'))
                if (startsWith('L')) {
                    append(';')
                }
            }
        }
    }.toString()

internal fun String.parseClassSignature(): String {
    return if (startsWith('L')) {
        substring(1).replace(";", "").replace('/', '.')
    } else if (startsWith("[")) {
        val arrayDepth = count { c -> c == '[' }
        StringBuilder(substring(arrayDepth).parseClassSignature()).apply { repeat(arrayDepth) { append("[]") } }.toString()
    } else {
        when (this) {
            "Z" -> "boolean"
            "B" -> "byte"
            "C" -> "char"
            "S" -> "short"
            "I" -> "int"
            "J" -> "long"
            "F" -> "float"
            "D" -> "double"
            "V" -> "void"
            else -> throw IllegalArgumentException("unknown signature: $this")
        }
    }

}
