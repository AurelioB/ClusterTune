package com.wuyr.jdwp_injector.debugger

import com.wuyr.jdwp_injector.exception.JdwpException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * see [https://docs.oracle.com/javase/8/docs/technotes/guides/jpda/jdwp-spec.html]
 *
 * @author wuyr
 * @github https://github.com/wuyr/jdwp-injector-for-android
 * @since 2024-02-03 上午11:49
 */
internal class JdwpMessage private constructor(private var data: ByteBuffer) {

    companion object {
        private var uniqueID = 0
        var fieldSize = 0
        var methodSize = 0
        var objectSize = 0
        var classSize = 0

        fun create(cmdSet: Int, cmd: Int) = JdwpMessage(ByteBuffer.allocate(11).order(ByteOrder.BIG_ENDIAN)).apply {
            data.position(4)
            data.putInt(++uniqueID) // id
            data.put(0) // flags
            data.put(cmdSet.toByte()) // cmdSet
            data.put(cmd.toByte()) // cmd
        }

        fun fromByteArray(bytes: ByteArray) = JdwpMessage(ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)).apply {
            data.position(8)
            if (getByte().toInt() == -128) {
                errorCode = getShort().toInt()
                parseErrorCode()
            } else {
                data.position(11)
            }
        }
    }

    fun getBoolean() = data.get().toInt() == 1

    fun getByte() = data.get()

    fun getChar() = data.getChar()

    fun getClassId() = getID(classSize)

    fun getDouble() = data.getDouble()

    fun getFieldId() = getID(fieldSize)

    fun getFloat() = data.getFloat()

    fun getInt() = data.getInt()

    fun getLong() = data.getLong()

    fun getMethodId() = getID(methodSize)

    fun getString() = String(ByteArray(data.getInt()).apply { data.get(this) })

    fun getObjectId() = getID(objectSize)

    fun getShort() = data.getShort()

    var errorCode = 0

    /**
     * see [https://docs.oracle.com/javase/8/docs/platform/jpda/jdwp/jdwp-protocol.html]
     */
    fun parseErrorCode() {
        throw JdwpException(
            when (errorCode) {
                10 -> "Passed thread is null, is not a valid thread or has exited."
                13 -> "Specified thread has not been suspended by an event."
                20 -> "This reference type has been unloaded and garbage collected."
                21 -> "Invalid class."
                22 -> "Class has been loaded but not yet prepared."
                23 -> "Invalid method."
                24 -> "Invalid location."
                25 -> "Invalid field."
                99 -> "The functionality is not implemented in this virtual machine."
                102 -> "The specified event type id is not recognized."
                112 -> "The virtual machine is not running."
                506 -> "The string is invalid."
                512 -> "The count is invalid."
                else -> return
            }
        )
    }

    fun getPrimitiveTypeValue(type: Byte): Any = when (type.toInt()) {
        PRIMITIVE_TYPE_BYTE -> getByte()
        PRIMITIVE_TYPE_CHAR -> getChar()
        PRIMITIVE_TYPE_DOUBLE -> getDouble()
        PRIMITIVE_TYPE_FLOAT -> getFloat()
        PRIMITIVE_TYPE_INT -> getInt()
        PRIMITIVE_TYPE_LONG -> getLong()
        PRIMITIVE_TYPE_SHORT -> getShort()
        PRIMITIVE_TYPE_VOID -> Void.TYPE
        PRIMITIVE_TYPE_BOOLEAN -> getBoolean()
        else -> throw IllegalArgumentException("unknown primitive type: $type")
    }

    private var size = data.capacity()
        set(value) {
            ensureCapacityInternal(value)
            field = value
        }

    fun putBoolean(value: Boolean) {
        size++
        data.put(if (value) 1 else 0)
    }

    fun putByte(value: Int) {
        size++
        data.put(value.toByte())
    }

    fun putChar(value: Char) {
        size += 2
        data.putChar(value)
    }

    fun putClassId(id: Long) {
        putID(classSize, id)
    }

    fun putDouble(value: Double) {
        size += 8
        data.putDouble(value)
    }

    fun putFieldId(id: Long) {
        putID(fieldSize, id)
    }

    fun putFloat(value: Float) {
        size += 4
        data.putFloat(value)
    }

    fun putInt(value: Int) {
        size += 4
        data.putInt(value)
    }

    fun putLong(value: Long) {
        size += 8
        data.putLong(value)
    }

    fun putMethodId(id: Long) {
        putID(methodSize, id)
    }

    fun putString(content: String) {
        val contentBytes = content.toByteArray()
        size += 4
        data.putInt(contentBytes.size)
        size += contentBytes.size
        data.put(contentBytes)
    }

    fun putObjectId(id: Long) {
        putID(objectSize, id)
    }

    fun putShort(value: Short) {
        size += 2
        data.putShort(value)
    }

    fun toByteArray() = ByteArray(size).apply {
        data.position(0)
        data.putInt(size) // length
        data.array().copyInto(this, 0, 0, size)
    }

    private fun getID(idSize: Int) = when (idSize) {
        8 -> data.getLong()
        4 -> data.getInt().toLong()
        2 -> data.getShort().toLong()
        else -> throw IllegalArgumentException("illegal id size: $idSize")
    }

    private fun putID(idSize: Int, id: Long) {
        when (idSize) {
            8 -> {
                size += 8
                data.putLong(id)
            }

            4 -> {
                size += 4
                data.putInt(id.toInt())
            }

            2 -> {
                size += 2
                data.putShort(id.toShort())
            }
        }
    }

    fun putParamsData(paramsPairs: Array<out Pair<String, Any?>>, createStringObject: (String) -> Long) {
        putInt(paramsPairs.size) // params count
        paramsPairs.forEach { (type, value) ->
            if (type.isPrimitive) {
                if (value == null) {
                    throw IllegalArgumentException("params primitive type: $type value can not be null")
                }
                when (type) {
                    "byte" -> {
                        putByte(PRIMITIVE_TYPE_BYTE)
                        putByte(value as Int)
                    }

                    "char" -> {
                        putByte(PRIMITIVE_TYPE_CHAR)
                        putChar(value as Char)
                    }

                    "double" -> {
                        putByte(PRIMITIVE_TYPE_DOUBLE)
                        putDouble(value as Double)
                    }

                    "float" -> {
                        putByte(PRIMITIVE_TYPE_FLOAT)
                        putFloat(value as Float)
                    }

                    "int" -> {
                        putByte(PRIMITIVE_TYPE_INT)
                        putInt(value as Int)
                    }

                    "long" -> {
                        putByte(PRIMITIVE_TYPE_LONG)
                        putLong(value as Long)
                    }

                    "short" -> {
                        putByte(PRIMITIVE_TYPE_SHORT)
                        putShort(value as Short)
                    }

                    "boolean" -> {
                        putByte(PRIMITIVE_TYPE_BOOLEAN)
                        putBoolean(value as Boolean)
                    }

                    else -> throw IllegalArgumentException("unknown primitive param type: $type")
                }
            } else {
                val typeSignature = type.signature
                putByte(
                    if (typeSignature.startsWith("[")) REFERENCE_TYPE_ARRAY else when (typeSignature) {
                        "Ljava/lang/Class;" -> REFERENCE_TYPE_CLASS_OBJECT
                        "Ljava/lang/ThreadGroup;" -> REFERENCE_TYPE_THREAD_GROUP
                        "Ljava/lang/ClassLoader;" -> REFERENCE_TYPE_CLASS_LOADER
                        "Ljava/lang/String;" -> REFERENCE_TYPE_STRING
                        "Ljava/lang/Thread;" -> REFERENCE_TYPE_THREAD
                        else -> REFERENCE_TYPE_OBJECT
                    }
                )
                if (value == null) {
                    putObjectId(0)
                } else {
                    when (value) {
                        is String -> putObjectId(createStringObject(value))
                        is Long -> putObjectId(value)
                        else -> throw IllegalArgumentException("reference type: $type must be a String or Long value")
                    }
                }
            }
        }
    }

    private fun ensureCapacityInternal(minimumCapacity: Int) {
        // overflow-conscious code
        if (minimumCapacity - data.capacity() > 0) {
            data = ByteBuffer.allocate(newCapacity(minimumCapacity)).apply {
                put(data.array(), 0, size)
            }
        }
    }

    private fun newCapacity(minCapacity: Int): Int {
        // overflow-conscious code
        var newCapacity: Int = (data.capacity() shl 1) + 2
        if (newCapacity - minCapacity < 0) {
            newCapacity = minCapacity
        }
        return if (newCapacity <= 0 || Int.MAX_VALUE - 8 - newCapacity < 0) hugeCapacity(minCapacity) else newCapacity
    }

    private fun hugeCapacity(minCapacity: Int): Int {
        if (Int.MAX_VALUE - minCapacity < 0) { // overflow
            throw OutOfMemoryError()
        }
        return if (minCapacity > Int.MAX_VALUE - 8) minCapacity else Int.MAX_VALUE - 8
    }
}