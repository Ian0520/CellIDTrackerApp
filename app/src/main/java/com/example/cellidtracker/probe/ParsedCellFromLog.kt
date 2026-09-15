package com.example.cellidtracker.probe

import com.example.cellidtracker.CellTowerParams

data class ParsedCellFromLog(
    val mcc: Int,
    val mnc: Int,
    val lac: Int,
    val cid: Int
)

internal fun ParsedCellFromLog.toTowerParams(): CellTowerParams {
    return CellTowerParams(mcc = mcc, mnc = mnc, lac = lac, cid = cid, radioType = "lte")
}
