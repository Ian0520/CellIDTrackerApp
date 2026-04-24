package com.example.cellidtracker.probe

data class ParsedCellFromLog(
    val mcc: Int,
    val mnc: Int,
    val lac: Int,
    val cid: Int
)
