package com.wuyr.jdwp_injector.debugger

import com.wuyr.jdwp_injector.adb.AdbClient
import java.io.Closeable
import java.lang.reflect.InvocationTargetException

/**
 * @author wuyr
 * @github https://github.com/wuyr/jdwp-injector-for-android
 * @since 2024-01-30 下午2:16
 */
class Debugger(private var adb: AdbClient) : Closeable {

    init {
        initIdSizes()
    }

    private fun initIdSizes() {
        JdwpMessage.fromByteArray(
            adb.sendCommand2jdwp(
                *JdwpMessage.create(COMMAND_SET_VIRTUAL_MACHINE, COMMAND_VIRTUAL_MACHINE_ID_SIZES).toByteArray()
            )
        ).apply {
            JdwpMessage.fieldSize = getInt()
            JdwpMessage.methodSize = getInt()
            JdwpMessage.objectSize = getInt()
            JdwpMessage.classSize = getInt()
            getInt() // frameSize
        }
    }

    fun findClassId(signature: String): Long {
        var classId: Long
        JdwpMessage.fromByteArray(
            adb.sendCommand2jdwp(
                *JdwpMessage.create(COMMAND_SET_VIRTUAL_MACHINE, COMMAND_VIRTUAL_MACHINE_CLASSES_BY_SIGNATURE).apply {
                    putString(signature)
                }.toByteArray()
            )
        ).apply {
            if (getInt() /* count */ == 0) {
                throw ClassNotFoundException("class $signature not found")
            }
            getByte() // class type
            classId = getClassId()
            if (classId == 0L) {
                throw ClassNotFoundException("class $signature not found")
            }
            getInt() // class status
        }
        return classId
    }

    fun findClassIdByObjectId(objectId: Long) = JdwpMessage.fromByteArray(
        adb.sendCommand2jdwp(
            *JdwpMessage.create(COMMAND_SET_OBJECT_REFERENCE, COMMAND_OBJECT_REFERENCE_REFERENCE_TYPE).apply {
                putObjectId(objectId)
            }.toByteArray()
        )
    ).run {
        getByte() // class type
        getClassId()
    }

    fun findFieldId(classId: Long, name: String, signature: String = ""): Long {
        JdwpMessage.fromByteArray(
            adb.sendCommand2jdwp(
                *JdwpMessage.create(COMMAND_SET_REFERENCE_TYPE, COMMAND_REFERENCE_TYPE_FIELDS).apply {
                    putClassId(classId)
                }.toByteArray()
            )
        ).apply {
            repeat(getInt() /*count*/) {
                val fieldId = getFieldId() // fieldID
                val fieldName = getString() // name
                val fieldSignature = getString() // signature
                getInt() // modifiers
                if (name == fieldName && (signature.isEmpty() || signature == fieldSignature)) {
                    return fieldId
                }
            }
        }
        throw NoSuchFieldException("field not found: $name $signature")
    }

    fun findMethodId(classId: Long, name: String, signature: String = ""): Long {
        JdwpMessage.fromByteArray(
            adb.sendCommand2jdwp(
                *JdwpMessage.create(COMMAND_SET_REFERENCE_TYPE, COMMAND_REFERENCE_TYPE_METHODS).apply {
                    putClassId(classId)
                }.toByteArray()
            )
        ).apply {
            repeat(getInt() /*count*/) {
                val methodId = getMethodId()
                val methodName = getString()
                val methodSignature = getString()
                getInt() // modifiers
                if (name == methodName && (signature.isEmpty() || signature == methodSignature)) {
                    return methodId
                }
            }
        }
        throw NoSuchMethodException("method not found: $name$signature")
    }

    fun findSignatureByClassId(classId: Long) = JdwpMessage.fromByteArray(
        adb.sendCommand2jdwp(
            *JdwpMessage.create(COMMAND_SET_REFERENCE_TYPE, COMMAND_REFERENCE_TYPE_SIGNATURE).apply {
                putClassId(classId)
            }.toByteArray()
        )
    ).getString()

    fun newInstance(targetClassName: String, threadId: Long, vararg paramsPairs: Pair<String, Any?>): Pair<Byte, Long> {
        val declaringClassId = findClassId(targetClassName.signature)
        val methodId = findMethodId(declaringClassId, "<init>", getMethodSignature(*paramsPairs.map { it.first }.toTypedArray()))
        return newInstance(declaringClassId, methodId, threadId, *paramsPairs)
    }

    fun newInstance(declaringClassId: Long, constructorId: Long, threadId: Long, vararg paramsPairs: Pair<String, Any?> = emptyArray()): Pair<Byte, Long> {
        JdwpMessage.fromByteArray(
            adb.sendCommand2jdwp(
                *JdwpMessage.create(COMMAND_SET_CLASS_TYPE, COMMAND_CLASS_TYPE_NEW_INSTANCE).apply {
                    putClassId(declaringClassId)
                    putObjectId(threadId)
                    putMethodId(constructorId)
                    putParamsData(paramsPairs) { createString(it) }
                    putInt(0) // options
                }.toByteArray()
            )
        ).apply {
            // new object
            val newObjectType = getByte()
            val newObjectId = getObjectId()
            // exception object
            getByte() // exceptionObjectType
            val exceptionObjectId = getObjectId()
            if (exceptionObjectId == 0L) {
                // no exception
                return newObjectType to newObjectId
            } else {
                throw InvocationTargetException(
                    null, "An exception occurred during create new instance(class id=$declaringClassId): ${getExceptionMessage(exceptionObjectId, threadId)}"
                )
            }
        }
    }

    fun invokeInstanceMethod(objectId: Long, targetClassName: String, methodName: String, returnTypeName: String, threadId: Long, vararg paramsPairs: Pair<String, Any?>): Pair<Byte, Any> {
        val declaringClassId = findClassId(targetClassName.signature)
        val methodId = findMethodId(declaringClassId, methodName, getMethodSignature(*paramsPairs.map { it.first }.toTypedArray(), returnTypeName = returnTypeName))
        return invokeInstanceMethod(objectId, declaringClassId, methodId, threadId, *paramsPairs)
    }

    fun invokeInstanceMethod(objectId: Long, declaringClassId: Long, methodId: Long, threadId: Long, vararg paramsPairs: Pair<String, Any?> = emptyArray()): Pair<Byte, Any> {
        JdwpMessage.fromByteArray(
            adb.sendCommand2jdwp(
                *JdwpMessage.create(COMMAND_SET_OBJECT_REFERENCE, COMMAND_OBJECT_REFERENCE_INVOKE_METHOD).apply {
                    putObjectId(objectId)
                    putObjectId(threadId)
                    putClassId(declaringClassId)
                    putMethodId(methodId)
                    putParamsData(paramsPairs) { createString(it) }
                    putInt(0) // options
                }.toByteArray()
            )
        ).apply {
            // return value
            val returnValueType = getByte()
            val returnValue = if (returnValueType.isObject) {
                getObjectId() // returnValueId
            } else {
                getPrimitiveTypeValue(returnValueType)
            }
            // exception object
            getByte() // exceptionObjectType
            val exceptionObjectId = getObjectId()
            if (exceptionObjectId == 0L) {
                // no exception
                return returnValueType to returnValue
            } else {
                throw InvocationTargetException(
                    null, "An exception occurred during invoke method(id=$methodId): ${getExceptionMessage(exceptionObjectId, threadId)}"
                )
            }
        }
    }

    fun invokeStaticMethod(targetClassName: String, methodName: String, returnTypeName: String, threadId: Long, vararg paramsPairs: Pair<String, Any?>): Pair<Byte, Any> {
        val declaringClassId = findClassId(targetClassName.signature)
        val methodId = findMethodId(declaringClassId, methodName, getMethodSignature(*paramsPairs.map { it.first }.toTypedArray(), returnTypeName = returnTypeName))
        return invokeStaticMethod(declaringClassId, methodId, threadId, *paramsPairs)
    }

    fun invokeStaticMethod(declaringClassId: Long, methodId: Long, threadId: Long, vararg paramsPairs: Pair<String, Any?> = emptyArray()): Pair<Byte, Any> {
        JdwpMessage.fromByteArray(
            adb.sendCommand2jdwp(
                *JdwpMessage.create(COMMAND_SET_CLASS_TYPE, COMMAND_CLASS_TYPE_INVOKE_METHOD).apply {
                    putClassId(declaringClassId)
                    putObjectId(threadId)
                    putMethodId(methodId)
                    putParamsData(paramsPairs) { createString(it) }
                    putInt(0) // options
                }.toByteArray()
            )
        ).apply {
            // return value
            val returnValueType = getByte()
            val returnValue = if (returnValueType.isObject) getObjectId() /* returnValueId */ else getPrimitiveTypeValue(returnValueType)
            // exception object
            getByte() // exceptionObjectType
            val exceptionObjectId = getObjectId()
            if (exceptionObjectId == 0L) {
                // no exception
                return returnValueType to returnValue
            } else {
                throw InvocationTargetException(
                    null, "An exception occurred during invoke method(id=$methodId):\n${getExceptionMessage(exceptionObjectId, threadId)}"
                )
            }
        }
    }

    fun setFieldModificationWatch(classId: Long, fieldId: Long) = JdwpMessage.fromByteArray(
        adb.sendCommand2jdwp(
            *JdwpMessage.create(COMMAND_SET_EVENT_REQUEST, COMMAND_EVENT_REQUEST_SET).apply {
                putByte(EVENT_KIND_FIELD_MODIFICATION)
                putByte(SUSPEND_POLICY_ALL)
                putInt(1) // length
                putByte(EVENT_REQUEST_CASE_FIELD_ONLY)
                putClassId(classId)
                putFieldId(fieldId)
            }.toByteArray()
        )
    ).getInt()

    fun setAndWaitForModificationEventArrive(targetClassName: String, fieldName: String, fieldTypeName: String, beforeWait: () -> Unit): Long {
        val classId = findClassId(targetClassName.signature)
        val fieldId = findFieldId(classId, fieldName, fieldTypeName.signature)
        val requestId = setFieldModificationWatch(classId, fieldId)
        beforeWait()
        return waitForModificationEventArrive(requestId).also { clearFieldModificationWatch(requestId) }
    }

    fun waitForModificationEventArrive(requestId: Int) = JdwpMessage.fromByteArray(adb.pollJdwpMessage()).run {
        getByte() // suspendPolicy
        repeat(getInt() /*eventsCount*/) {
            getByte() // eventKind
            val currentRequestId = getInt()
            val currentThreadId = getObjectId()
            // location
            getByte() // locationClassType
            getObjectId() // classId
            getMethodId() // methodId
            getLong() // codeIndex
            getByte() // class type
            getClassId() // typeId
            getFieldId() // fieldId
            // object
            getByte() // objectType
            getObjectId() // objectId
            // value
            val newValueObjectType = getByte() // newValueObjectType
            if (newValueObjectType.isObject) {
                val newValueId = getObjectId()
                getReferenceTypeValue(newValueObjectType, newValueId) // newValue
            } else {
                getPrimitiveTypeValue(newValueObjectType) // newValue
            }
            if (currentRequestId == requestId) {
                return@run currentThreadId
            }
        }
        throw IllegalStateException("modification event lost, id: $requestId")
    }

    fun clearFieldModificationWatch(requestID: Int) {
        adb.sendCommand2jdwp(
            *JdwpMessage.create(COMMAND_SET_EVENT_REQUEST, COMMAND_EVENT_REQUEST_CLEAR).apply {
                putByte(EVENT_KIND_FIELD_MODIFICATION)
                putInt(requestID)
            }.toByteArray()
        )
    }

    fun getExceptionMessage(exceptionObjectId: Long, threadId: Long) = findClassId("android.util.Log".signature).let { logClassId ->
        getStringValueById(
            invokeStaticMethod(
                logClassId, findMethodId(
                    logClassId, "getStackTraceString",
                    getMethodSignature("java.lang.Throwable", returnTypeName = "String")
                ), threadId, "java.lang.Throwable" to exceptionObjectId
            ).second as Long
        )
    }

    fun getReferenceTypeValue(type: Byte, valueId: Long): String? = if (valueId == 0L) null else when (type.toInt()) {
        REFERENCE_TYPE_OBJECT, REFERENCE_TYPE_ARRAY, REFERENCE_TYPE_CLASS_OBJECT,
        REFERENCE_TYPE_THREAD_GROUP, REFERENCE_TYPE_CLASS_LOADER, REFERENCE_TYPE_THREAD -> {
            "${findSignatureByClassId(findClassIdByObjectId(valueId)).parseClassSignature()}(id=$valueId)"
        }

        REFERENCE_TYPE_STRING -> getStringValueById(valueId)

        else -> throw IllegalArgumentException("unknown reference type: $type")
    }

    fun getStringValueById(valueId: Long) = JdwpMessage.fromByteArray(
        adb.sendCommand2jdwp(
            *JdwpMessage.create(COMMAND_SET_STRING_REFERENCE, COMMAND_STRING_REFERENCE_VALUE).apply {
                putObjectId(valueId)
            }.toByteArray()
        )
    ).getString()

    fun getObjectId(classId: Long, fieldId: Long) = JdwpMessage.fromByteArray(
        adb.sendCommand2jdwp(
            *JdwpMessage.create(COMMAND_SET_REFERENCE_TYPE, COMMAND_REFERENCE_TYPE_GET_VALUES).apply {
                putClassId(classId)
                putInt(1) // fields count
                putFieldId(fieldId)
            }.toByteArray()
        )
    ).run {
        getInt() // values count
        val valueType = getByte()
        if (valueType.isObject) {
            getObjectId()
        } else {
            throw IllegalArgumentException("field type must be a reference type")
        }
    }

    fun getObjectFieldValue(objectId: Long, fieldId: Long) = JdwpMessage.fromByteArray(
        adb.sendCommand2jdwp(
            *JdwpMessage.create(COMMAND_SET_OBJECT_REFERENCE, COMMAND_OBJECT_REFERENCE_GET_VALUES).apply {
                putObjectId(objectId)
                putInt(1) // fields count
                putFieldId(fieldId)
            }.toByteArray()
        )
    ).run {
        getInt() // values count
        val valueType = getByte()
        if (valueType.isObject) {
            val valueId = getObjectId()
            if (valueId == 0L) null else getReferenceTypeValue(valueType, valueId)
        } else {
            getPrimitiveTypeValue(valueType)
        }
    }

    fun createString(content: String) = JdwpMessage.fromByteArray(
        adb.sendCommand2jdwp(
            *JdwpMessage.create(COMMAND_SET_VIRTUAL_MACHINE, COMMAND_VIRTUAL_MACHINE_CREATE_STRING).apply {
                putString(content)
            }.toByteArray()
        )
    ).getObjectId()

    fun resumeVM() {
        adb.sendCommand2jdwp(
            *JdwpMessage.create(COMMAND_SET_VIRTUAL_MACHINE, COMMAND_VIRTUAL_MACHINE_RESUME).toByteArray()
        )
    }

    fun dispose() {
        adb.sendCommand2jdwp(
            *JdwpMessage.create(COMMAND_SET_VIRTUAL_MACHINE, COMMAND_VIRTUAL_MACHINE_DISPOSE).toByteArray()
        )
    }

    override fun close() = adb.close()
}