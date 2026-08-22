package com.wuyr.jdwp_injector.debugger

/**
 * see [https://docs.oracle.com/javase/8/docs/technotes/guides/jpda/jdwp-spec.html]
 *
 * see [https://docs.oracle.com/javase/8/docs/platform/jpda/jdwp/jdwp-protocol.html]
 *
 * @author wuyr
 * @github https://github.com/wuyr/jdwp-injector-for-android
 * @since 2024-02-01 下午1:51
 */
internal const val COMMAND_SET_VIRTUAL_MACHINE = 1
internal const val COMMAND_VIRTUAL_MACHINE_CLASSES_BY_SIGNATURE = 2
internal const val COMMAND_VIRTUAL_MACHINE_DISPOSE  = 6
internal const val COMMAND_VIRTUAL_MACHINE_ID_SIZES = 7
internal const val COMMAND_VIRTUAL_MACHINE_SUSPEND = 8
internal const val COMMAND_VIRTUAL_MACHINE_RESUME = 9
internal const val COMMAND_VIRTUAL_MACHINE_CREATE_STRING = 11

internal const val COMMAND_SET_REFERENCE_TYPE = 2
internal const val COMMAND_REFERENCE_TYPE_SIGNATURE = 1
internal const val COMMAND_REFERENCE_TYPE_FIELDS = 4
internal const val COMMAND_REFERENCE_TYPE_METHODS = 5
internal const val COMMAND_REFERENCE_TYPE_GET_VALUES = 6

internal const val COMMAND_SET_CLASS_TYPE = 3
internal const val COMMAND_CLASS_TYPE_INVOKE_METHOD = 3
internal const val COMMAND_CLASS_TYPE_NEW_INSTANCE = 4

internal const val COMMAND_SET_OBJECT_REFERENCE = 9
internal const val COMMAND_OBJECT_REFERENCE_REFERENCE_TYPE = 1
internal const val COMMAND_OBJECT_REFERENCE_GET_VALUES = 2
internal const val COMMAND_OBJECT_REFERENCE_INVOKE_METHOD = 6

internal const val COMMAND_SET_STRING_REFERENCE = 10
internal const val COMMAND_STRING_REFERENCE_VALUE = 1

internal const val COMMAND_SET_EVENT_REQUEST = 15
internal const val COMMAND_EVENT_REQUEST_SET = 1
internal const val COMMAND_EVENT_REQUEST_CLEAR = 2

internal const val EVENT_KIND_BREAKPOINT = 2
internal const val EVENT_KIND_FIELD_ACCESS = 20
internal const val EVENT_KIND_FIELD_MODIFICATION = 21
internal const val EVENT_KIND_METHOD_ENTRY = 40
internal const val EVENT_KIND_METHOD_EXIT = 41

internal const val EVENT_REQUEST_CASE_LOCATION_ONLY = 7
internal const val EVENT_REQUEST_CASE_FIELD_ONLY = 9
internal const val EVENT_REQUEST_CASE_INSTANCE_ONLY = 11

internal const val SUSPEND_POLICY_NONE = 0
internal const val SUSPEND_POLICY_EVENT_THREAD = 1
internal const val SUSPEND_POLICY_ALL = 2

internal const val PRIMITIVE_TYPE_BYTE = 66
internal const val PRIMITIVE_TYPE_CHAR = 67
internal const val PRIMITIVE_TYPE_DOUBLE = 68
internal const val PRIMITIVE_TYPE_FLOAT = 70
internal const val PRIMITIVE_TYPE_INT = 73
internal const val PRIMITIVE_TYPE_LONG = 74
internal const val PRIMITIVE_TYPE_SHORT = 83
internal const val PRIMITIVE_TYPE_VOID = 86
internal const val PRIMITIVE_TYPE_BOOLEAN = 90

internal const val REFERENCE_TYPE_OBJECT = 76
internal const val REFERENCE_TYPE_ARRAY = 91
internal const val REFERENCE_TYPE_CLASS_OBJECT = 99
internal const val REFERENCE_TYPE_THREAD_GROUP = 103
internal const val REFERENCE_TYPE_CLASS_LOADER = 108
internal const val REFERENCE_TYPE_STRING = 115
internal const val REFERENCE_TYPE_THREAD = 116