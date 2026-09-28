package com.nfcpassportreader.utils

fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
